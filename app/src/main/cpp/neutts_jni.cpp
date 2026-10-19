// JNI runtime for NeuTTS Nano (org.localvoice.tts.backend.NeuTtsNative): llama.cpp backbone with prompt KV
// prefix reuse, eSpeak NG IPA phonemes, NeuCodec ONNX decoder through the shared libonnxruntime.so.
// Text crosses JNI as UTF-8 byte arrays (JNI "modified UTF-8" mangles supplementary characters).
#include <jni.h>
#include <android/log.h>
#include <sys/auxv.h>
#include <algorithm>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>
#include "ggml-cpu.h"
#include "llama.h"
#include "onnxruntime_c_api.h"
#include <espeak-ng/speak_lib.h>

#define LOG_TAG "LocalVoiceNeuTTS"

namespace {

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    ggml_threadpool *pool = nullptr, *batchPool = nullptr;  // persistent workers; without them ggml spawns threads per graph
    const llama_vocab *vocab = nullptr;
    std::vector<llama_token> cached;  // tokens whose KV entries are in ctx, in order
    int nCtx = 0, nBatch = 0;
    std::atomic<bool> abort{false};
    OrtEnv *env = nullptr;
    OrtSession *session = nullptr;
    OrtMemoryInfo *memory = nullptr;
    std::string input, output;
};

std::mutex espeakLock;  // eSpeak NG is process-global state
std::string espeakPath, espeakVoice;

const OrtApi *ort() {
    static const OrtApi *api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    return api;
}

void throwJava(JNIEnv *env, const std::string &message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(type, message.c_str());
}

bool ortOk(JNIEnv *env, OrtStatus *status, const char *what) {
    if (!status) return true;
    std::string message = std::string(what) + ": " + ort()->GetErrorMessage(status);
    ort()->ReleaseStatus(status);
    throwJava(env, message);
    return false;
}

std::string bytes(JNIEnv *env, jbyteArray array) {
    std::string out(env->GetArrayLength(array), '\0');
    env->GetByteArrayRegion(array, 0, (jsize) out.size(), reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray toBytes(JNIEnv *env, const std::string &value) {
    jbyteArray out = env->NewByteArray((jsize) value.size());
    if (out) env->SetByteArrayRegion(out, 0, (jsize) value.size(), reinterpret_cast<const jbyte *>(value.data()));
    return out;
}

std::string jstring8(JNIEnv *env, jstring value) {
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

// ggml is compiled for armv8.2-a+dotprod+fp16 (arm64) or AVX2/FMA/F16C/BMI2 (x86_64); refuse other CPUs before any
// of that code runs instead of crashing the service with SIGILL.
bool cpuSupported() {
#if defined(__aarch64__)
    unsigned long hw = getauxval(AT_HWCAP);
    return (hw & (1UL << 20)) && (hw & (1UL << 9)) && (hw & (1UL << 10));  // asimddp, fphp, asimdhp
#elif defined(__x86_64__)
    return __builtin_cpu_supports("avx2") && __builtin_cpu_supports("fma") && __builtin_cpu_supports("f16c") &&
           __builtin_cpu_supports("bmi2");
#else
    return false;
#endif
}

void quietLog(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_WARN || level == GGML_LOG_LEVEL_ERROR) __android_log_write(ANDROID_LOG_WARN, LOG_TAG, text);
}

void release(Engine *e) {
    if (!e) return;
    if (e->sampler) llama_sampler_free(e->sampler);
    if (e->ctx) llama_free(e->ctx);
    if (e->pool) ggml_threadpool_free(e->pool);
    if (e->batchPool) ggml_threadpool_free(e->batchPool);
    if (e->model) llama_model_free(e->model);
    if (e->session) ort()->ReleaseSession(e->session);
    if (e->memory) ort()->ReleaseMemoryInfo(e->memory);
    if (e->env) ort()->ReleaseEnv(e->env);
    delete e;
}

bool openDecoder(JNIEnv *env, Engine *e, const std::string &path, int threads) {
    if (!ort()) { throwJava(env, "ONNX Runtime does not provide C API version " + std::to_string(ORT_API_VERSION)); return false; }
    OrtSessionOptions *options = nullptr;
    if (!ortOk(env, ort()->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "neucodec", &e->env), "CreateEnv")) return false;
    if (!ortOk(env, ort()->CreateSessionOptions(&options), "CreateSessionOptions")) return false;
    bool ok = ortOk(env, ort()->SetIntraOpNumThreads(options, threads), "SetIntraOpNumThreads") &&
              ortOk(env, ort()->SetSessionGraphOptimizationLevel(options, ORT_ENABLE_ALL), "SetGraphOptimization") &&
              // Decoder and backbone alternate on the same cores: idle ORT workers must not spin.
              ortOk(env, ort()->AddSessionConfigEntry(options, "session.intra_op.allow_spinning", "0"), "allow_spinning") &&
              ortOk(env, ort()->CreateSession(e->env, path.c_str(), options, &e->session), "CreateSession");
    ort()->ReleaseSessionOptions(options);
    if (!ok) return false;
    OrtAllocator *allocator = nullptr;
    if (!ortOk(env, ort()->GetAllocatorWithDefaultOptions(&allocator), "allocator")) return false;
    char *name = nullptr;
    if (!ortOk(env, ort()->SessionGetInputName(e->session, 0, allocator, &name), "input name")) return false;
    e->input = name;
    allocator->Free(allocator, name);
    if (!ortOk(env, ort()->SessionGetOutputName(e->session, 0, allocator, &name), "output name")) return false;
    e->output = name;
    allocator->Free(allocator, name);
    return ortOk(env, ort()->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &e->memory), "memory info");
}

bool initEspeak(JNIEnv *env, const std::string &path, const std::string &voice) {
    std::lock_guard<std::mutex> guard(espeakLock);
    if (path == espeakPath && voice == espeakVoice) return true;
    if (!espeakPath.empty()) espeak_Terminate();
    espeakPath.clear();
    if (espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, path.c_str(), espeakINITIALIZE_DONT_EXIT) < 0) {
        throwJava(env, "eSpeak NG initialization failed");
        return false;
    }
    if (espeak_SetVoiceByName(voice.c_str()) != EE_OK) {
        espeak_Terminate();
        throwJava(env, "eSpeak NG voice not found: " + voice);
        return false;
    }
    espeakPath = path;
    espeakVoice = voice;
    return true;
}

bool abortRequested(void *data) { return static_cast<Engine *>(data)->abort.load(); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_org_localvoice_tts_backend_NeuTtsNative_open(JNIEnv *env, jclass, jstring gguf,
        jstring decoder, jstring espeakData, jstring voice, jint threads, jint batchThreads, jint decoderThreads,
        jint contextSize) {
    if (!cpuSupported()) { throwJava(env, "This CPU lacks the instructions the NeuTTS runtime is built for"); return 0; }
    if (!initEspeak(env, jstring8(env, espeakData), jstring8(env, voice))) return 0;
    static std::once_flag backend;
    std::call_once(backend, [] { llama_log_set(quietLog, nullptr); llama_backend_init(); });
    auto *e = new Engine();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    e->model = llama_model_load_from_file(jstring8(env, gguf).c_str(), mp);
    if (!e->model) { release(e); throwJava(env, "Cannot load NeuTTS backbone"); return 0; }
    e->vocab = llama_model_get_vocab(e->model);
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) contextSize;
    cp.n_batch = cp.n_ubatch = 512;
    cp.n_seq_max = 1;
    cp.n_threads = threads;  // single-token steps: barrier cost grows with threads on big.LITTLE
    cp.n_threads_batch = batchThreads;
    cp.no_perf = true;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;  // ~20% faster single-token steps on Pixel 7 Pro CPU than auto (enabled)
    cp.abort_callback = abortRequested;
    cp.abort_callback_data = e;
    e->ctx = llama_init_from_model(e->model, cp);
    if (!e->ctx) { release(e); throwJava(env, "Cannot create NeuTTS context"); return 0; }
    ggml_threadpool_params pool = ggml_threadpool_params_default(threads), batch = ggml_threadpool_params_default(batchThreads);
    e->pool = ggml_threadpool_new(&pool);
    e->batchPool = ggml_threadpool_new(&batch);
    if (!e->pool || !e->batchPool) { release(e); throwJava(env, "Cannot create NeuTTS thread pools"); return 0; }
    llama_attach_threadpool(e->ctx, e->pool, e->batchPool);
    e->nCtx = (int) llama_n_ctx(e->ctx);
    e->nBatch = (int) llama_n_batch(e->ctx);
    if (!openDecoder(env, e, jstring8(env, decoder), decoderThreads)) { release(e); return 0; }
    return reinterpret_cast<jlong>(e);
}

// Token id of a single special/literal piece, or -1.
JNIEXPORT jint JNICALL Java_org_localvoice_tts_backend_NeuTtsNative_token(JNIEnv *env, jclass, jlong handle, jstring piece) {
    auto *e = reinterpret_cast<Engine *>(handle);
    std::string text = jstring8(env, piece);
    llama_token out[2];
    int n = llama_tokenize(e->vocab, text.c_str(), (int32_t) text.size(), out, 2, false, true);
    return n == 1 ? out[0] : -1;
}

JNIEXPORT jint JNICALL Java_org_localvoice_tts_backend_NeuTtsNative_contextSize(JNIEnv *, jclass, jlong handle) {
    return reinterpret_cast<Engine *>(handle)->nCtx;
}

// Raw espeak_TextToPhonemes output exactly as phonemizer's EspeakWrapper.text_to_phonemes(tie=False) joins it.
JNIEXPORT jbyteArray JNICALL Java_org_localvoice_tts_backend_NeuTtsNative_phonemes(JNIEnv *env, jclass, jbyteArray text) {
    std::string input = bytes(env, text);
    std::lock_guard<std::mutex> guard(espeakLock);
    if (espeakPath.empty()) { throwJava(env, "eSpeak NG is not initialized"); return nullptr; }
    std::string result;
    const void *cursor = input.c_str();
    while (cursor != nullptr) {
        const char *phonemes = espeak_TextToPhonemes(&cursor, espeakCHARS_UTF8, ('_' << 8) | 0x02);
        if (phonemes && *phonemes) {
            if (!result.empty()) result += ' ';
            result += phonemes;
        }
    }
    return toBytes(env, result);
}

// Tokenizes the prompt (BOS + special tokens parsed, like llama-cpp-python) and evaluates only the part after the
// longest prefix already in the KV cache. Returns {prompt tokens, reused tokens}.
JNIEXPORT jintArray JNICALL Java_org_localvoice_tts_backend_NeuTtsNative_prefill(JNIEnv *env, jclass, jlong handle,
        jbyteArray prompt, jint seed) {
    auto *e = reinterpret_cast<Engine *>(handle);
    e->abort = false;
    std::string text = bytes(env, prompt);
    std::vector<llama_token> tokens(text.size() + 8);
    int n = llama_tokenize(e->vocab, text.c_str(), (int32_t) text.size(), tokens.data(), (int32_t) tokens.size(), true, true);
    if (n <= 0 || n >= e->nCtx) { throwJava(env, "NeuTTS prompt does not fit the context: " + std::to_string(n)); return nullptr; }
    tokens.resize(n);
    size_t common = 0;
    while (common < e->cached.size() && common < tokens.size() && e->cached[common] == tokens[common]) common++;
    if (common == tokens.size()) common--;  // the last prompt token must be evaluated for fresh logits
    llama_memory_t memory = llama_get_memory(e->ctx);
    if (!llama_memory_seq_rm(memory, 0, (llama_pos) common, -1)) { llama_memory_clear(memory, true); common = 0; }
    e->cached.resize(common);
    for (size_t at = common; at < tokens.size(); at += e->nBatch) {
        int count = (int) std::min(tokens.size() - at, (size_t) e->nBatch);
        int status = llama_decode(e->ctx, llama_batch_get_one(tokens.data() + at, count));
        if (status != 0) {
            llama_memory_clear(memory, true);
            e->cached.clear();
            if (status != 2) throwJava(env, "NeuTTS prompt evaluation failed: " + std::to_string(status));
            return nullptr;  // aborted by cancel()
        }
        e->cached.insert(e->cached.end(), tokens.begin() + (long) at, tokens.begin() + (long) at + count);
    }
    if (e->sampler) llama_sampler_free(e->sampler);
    // llama-cpp-python's chain for NeuTTS: top_k 50, typical 1.0 (no-op), top_p 0.95, min_p 0.05, temperature 1.0.
    e->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(e->sampler, llama_sampler_init_top_k(50));
    llama_sampler_chain_add(e->sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(e->sampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(e->sampler, llama_sampler_init_temp(1.0f));
    llama_sampler_chain_add(e->sampler, llama_sampler_init_dist((uint32_t) seed));
    jintArray out = env->NewIntArray(2);
    jint values[2] = {n, (jint) common};
    if (out) env->SetIntArrayRegion(out, 0, 2, values);
    return out;
}

// Samples the next token; unless it is `stop`, evaluates it so the following call has logits.
// Returns the token, -1 when the context is full, -2 when cancelled.
JNIEXPORT jint JNICALL Java_org_localvoice_tts_backend_NeuTtsNative_next(JNIEnv *env, jclass, jlong handle, jint stop) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e->sampler) { throwJava(env, "NeuTTS prompt was not evaluated"); return -1; }
    llama_token token = llama_sampler_sample(e->sampler, e->ctx, -1);
    if (token == stop) return token;
    if ((int) e->cached.size() >= e->nCtx) return -1;
    int status = llama_decode(e->ctx, llama_batch_get_one(&token, 1));
    if (status != 0) {
        llama_memory_clear(llama_get_memory(e->ctx), true);
        e->cached.clear();
        if (status == 2) return -2;
        throwJava(env, "NeuTTS token evaluation failed: " + std::to_string(status));
        return -1;
    }
    e->cached.push_back(token);
    return token;
}

JNIEXPORT void JNICALL Java_org_localvoice_tts_backend_NeuTtsNative_cancel(JNIEnv *, jclass, jlong handle) {
    reinterpret_cast<Engine *>(handle)->abort = true;
}

// NeuCodec: int32 codes [1,1,F] -> float audio [1,1,N] at 24 kHz.
JNIEXPORT jfloatArray JNICALL Java_org_localvoice_tts_backend_NeuTtsNative_decode(JNIEnv *env, jclass, jlong handle,
        jintArray codes) {
    auto *e = reinterpret_cast<Engine *>(handle);
    jsize frames = env->GetArrayLength(codes);
    std::vector<int32_t> data(frames);
    env->GetIntArrayRegion(codes, 0, frames, reinterpret_cast<jint *>(data.data()));
    int64_t shape[3] = {1, 1, frames};
    OrtValue *in = nullptr, *out = nullptr;
    if (!ortOk(env, ort()->CreateTensorWithDataAsOrtValue(e->memory, data.data(), data.size() * sizeof(int32_t), shape, 3,
            ONNX_TENSOR_ELEMENT_DATA_TYPE_INT32, &in), "codes tensor")) return nullptr;
    const char *inNames[] = {e->input.c_str()}, *outNames[] = {e->output.c_str()};
    bool ok = ortOk(env, ort()->Run(e->session, nullptr, inNames, &in, 1, outNames, 1, &out), "NeuCodec decode");
    ort()->ReleaseValue(in);
    if (!ok) return nullptr;
    OrtTensorTypeAndShapeInfo *info = nullptr;
    size_t count = 0;
    float *audio = nullptr;
    ok = ortOk(env, ort()->GetTensorTypeAndShape(out, &info), "audio shape") &&
         ortOk(env, ort()->GetTensorShapeElementCount(info, &count), "audio size") &&
         ortOk(env, ort()->GetTensorMutableData(out, reinterpret_cast<void **>(&audio)), "audio data");
    if (info) ort()->ReleaseTensorTypeAndShapeInfo(info);
    jfloatArray result = ok ? env->NewFloatArray((jsize) count) : nullptr;
    if (result) env->SetFloatArrayRegion(result, 0, (jsize) count, audio);
    ort()->ReleaseValue(out);
    return result;
}

JNIEXPORT void JNICALL Java_org_localvoice_tts_backend_NeuTtsNative_close(JNIEnv *, jclass, jlong handle) {
    release(reinterpret_cast<Engine *>(handle));
}

}  // extern "C"
