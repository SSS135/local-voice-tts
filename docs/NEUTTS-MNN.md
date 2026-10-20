# NeuTTS Nano backbone on MNN vs llama.cpp (Pixel 7 Pro)

Measured 2026-10-20. **Verdict: reject.** On a warm phone MNN 3.6.1 was slower than llama.cpp b11729 in every matched configuration, and its 4-bit export was less accurate than the shipped Q4_0. Integrating it is not worth the work.

## Results (warm phone, interleaved ABAB)

Prefill: 512 prompt tokens at depth 0. Decode: 64 tokens at depth 512 (MNN decodes at 512–576 after its own prefill; llama-bench `tg64 @ d512`). Values are medians of the warm cycles, with ranges in brackets. RSS is peak `VmHWM` of the process, sampled every 0.2 s.

| Config | Head | Engine | pp512 tok/s | tg64 @ d512 tok/s | Peak RSS MB |
|---|---|---|---:|---:|---:|
| 4 threads, cpu4-7 (`taskset f0`), 9 cycles | full 194,256 rows, int8/Q8_0 | MNN `full-q4s32-h8` | 354 (319–355) | **45** (42–46) | 566 |
| | | llama.cpp `nano-Q4_0.gguf` | 488 (446–493) | **60** (58–61) | 468 |
| | speech only, 65,537 / 65,600 rows | MNN `speech-q4s32-h8` | 334 (321–358) | **63** (60–67) | 282 |
| | | llama.cpp `proxy-65600.gguf` | 489 (451–498) | **82** (47–86) | 301 |
| 2 threads, A78 only (`taskset 30`), 6 cycles | speech only | MNN `speech-q4s32-h8` | 270 (269–270) | **59** (58–60) | 282 |
| | | llama.cpp `proxy-65600.gguf` | 306 (299–334) | **70** (66–72) | 301 |

MNN/llama.cpp ratio: decode 0.75 (4 threads, full head), 0.77 (4 threads, speech head) and 0.84 (2 threads on the A78s); prefill 0.68–0.88.

- **Thermal state:** status 1–2 in all measured cycles, skin 41–43 °C, BIG 58–65 °C, A78 at 0.4–1.33 GHz, X1 mostly at 0.5–0.98 GHz. The 4-thread run warmed the phone for 5 min (cycles 1–8, discarded) after an idle phone at status 1 and skin 39 °C. The 2-thread run started 7 min after the 4-thread run ended. Its first 8 cycles (2.3 min) re-warmed the phone and are discarded.
- **Excluded:** 4-thread cycles 8 and 18 (11:36:10 and 11:41:46 UTC), where both engines dropped 20–50%. A Local Voice app install and dexopt ran on the phone during that window. One llama proxy cycle reported tg 47, which stays in the range and barely moves the median.
- **Not measured:** llama.cpp's 2-thread full head, MNN decode at depth 0, sustained runs longer than 10 min, and the end-to-end pipeline.
- **Cold, first cycle (one sample):** MNN 68 vs llama.cpp 84 tok/s decode with the full head at 4 threads. MNN was slower cold as well.
- **Load time:** MNN 0.3 s (speech) and 0.7 s (full).
- **Cause, inferred and not profiled:** MNN splits work between the X1 and A78 clusters by fixed rates (`cpuDecreaseRate`), while the warm X1s sat at 0.5 GHz. That fits the gap shrinking to 0.84 on the A78-only run, but the A78-only run still loses.

## Correctness (host, x86, before the phone)

Setup: a real NeuTTS prompt (584 tokens) built from the dave reference phones plus "The quick brown fox…" phones and dave's 372 reference codes, using the template from `NeuTtsFrontend.prompt`. Both models were teacher-forced for 32 steps along the HF argmax over END plus speech rows. The comparison covers the 65,537 END + speech logits.

| Model vs HF transformers fp32 | KL(hf‖x) mean / max | cos mean | top-5 overlap |
|---|---:|---:|---:|
| MNN 8-bit sym b32 (graph check) | 0.0017 / 0.0042 | 0.9997 | 4.88 |
| llama.cpp `nano-Q4_0.gguf` dequantized, fp32 math | 0.0143 / 0.0305 | 0.9971 | 4.21 |
| MNN 4-bit sym b32 (benchmarked) | 0.0427 / 0.0654 | 0.9936 | 4.06 |
| MNN 4-bit sym b32, speech head | 0.0408 / 0.0593 | 0.9939 | 4.00 |
| MNN 4-bit asym b64 (MNN default) | 0.0395 / 0.0549 | 0.9942 | 3.30 |

- **The export is correct only with a fix.** MNN's exporter silently ignores `rope_scaling: {type: linear, factor: 32}`. NeuTTS has that scaling, and the exporter's `Rotary` only handles dynamic, yarn and longrope. The wrapper divides `rotary.theta` by the factor before export. The 8-bit row proves the fixed graph matches HF.
- **MNN's 4-bit weights carry about 3× the KL of Q4_0** at equal 4.5 bits per weight. Changing the runtime to `precision`/`memory` `high` gives identical numbers, so the error comes from the weights. A WER/listening check was not run.

## Pins

| Item | Pin |
|---|---|
| MNN | tag 3.6.1 (latest release, 2026-07-23), commit d407447ed56c4121a11ccbd266dc184ca1ead0c2. KleidiAI 1.16.0 is fetched by CMake at configure time. |
| pymnn (host converter and runtime) | PyPI `MNN==3.6.1` win_amd64 |
| Export Python | CPython 3.12, torch 2.9.1+cpu, transformers 4.57.6 |
| Weights | HF `neuphonic/neutts-nano` @ ca2ba957804f0d80dd38ee819bdbd07311f95d15, `model.safetensors` sha256 00efc179c49eb1808b785c0722fbd6da78f46a51f0840c50b446bcb3e5e0c7ee (fp32, tied, vocab 194,256) |
| llama.cpp | b11729, from `docs/NEUTTS-PHONE-BENCH.md`. GGUFs on the phone in `/data/local/tmp/neutts-opt/`. `proxy-65600.gguf` is timing-only, with vocab and `token_embd` cut to 65,600 rows. |
| NDK / CMake | 29.0.14206865 / SDK CMake 3.22.1 + Ninja |

Token ids, read from `tokenizer.json`: END `<|SPEECH_GENERATION_END|>` = 128261, `<|speech_0..65535|>` = 128262–193797, and ids ≥ 193798 are phoneme tokens. The speech head is rows 128261–193797, so logit i corresponds to token 128261 + i.

## Artifacts

| Variant | Files | Bytes |
|---|---|---:|
| `full-q4s32-h8` | `llm.mnn` + `llm.mnn.weight` (head reused as embedding via `tie_embeddings`) | 185.0 MB |
| `speech-q4s32-h8` | `llm.mnn.weight` 106.0 MB + `embeddings_bf16.bin` 223.8 MB (read from disk per token) | 329.8 MB |
| `libMNN.so` arm64 (LLM engine, ARM82, KleidiAI, low-memory; `c++_static`) | 4.75 MB stripped, 1.95 MB gzip | |

The body is 4-bit sym with block 32 and fp16 scales, which is 4.5 bits per weight like Q4_0. The head is 8-bit sym with block 32, which matches llama.cpp's Q8_0 tied head.

Out-of-repo locations:
- scripts and raw logs: `X:\lv-mnn-out\scripts`
- exports: `X:\lv-mnn-out\<variant>`
- MNN tree and build: `%LOCALAPPDATA%\lv-mnn\{src,b}`
- phone copy: `/data/local/tmp/neutts-mnn`, about 520 MB

## Commands

Build (Git Bash, from `%LOCALAPPDATA%\lv-mnn\b`; `$NDK` is the NDK above, `$CM` is SDK `cmake/3.22.1/bin`):

```sh
"$CM/cmake" -G Ninja -DCMAKE_MAKE_PROGRAM="$CM/ninja.exe" ../src -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DCMAKE_BUILD_TYPE=Release -DANDROID_ABI=arm64-v8a -DANDROID_STL=c++_static -DANDROID_PLATFORM=android-33 \
  -DMNN_USE_LOGCAT=false -DMNN_BUILD_FOR_ANDROID_COMMAND=true -DMNN_USE_SSE=OFF -DMNN_BUILD_TOOLS=OFF \
  -DMNN_LOW_MEMORY=ON -DMNN_ARM82=ON -DMNN_SUPPORT_TRANSFORMER_FUSE=ON -DMNN_BUILD_LLM=ON -DLLM_SUPPORT_HTTP_RESOURCE=OFF \
  -DMNN_SEP_BUILD=OFF -DMNN_BUILD_SHARED_LIBS=ON -DMNN_OPENCL=OFF -DMNN_VULKAN=OFF -DMNN_BUILD_OPENCV=OFF
"$CM/ninja" MNN llm_bench
clang++ --target=aarch64-linux-android33 -O2 -std=c++17 -I../src/include -I../src/transformers/llm/engine/include \
  mnn_tts_bench.cpp -L. -lMNN -static-libstdc++ -o mnn_tts_bench
```

The device reports `i8sdot:1, fp16:1, i8mm:0`, and MNN selects ARM82 and sdot at runtime.

The export wrapper `mnn_export.py` imports `llmexport.LlmExporter`, then:
- divides `model.rotary.theta` by the linear rope factor;
- for `speech`, replaces `model.lm.lm` with a `Linear` over rows 128261–193797 and sets `tie_word_embeddings=False`.

```sh
PYTHONUTF8=1 uv run --no-project --python 3.12 --with MNN==3.6.1 --with torch==2.9.1 --with transformers==4.57.6 \
  --with onnx --with onnxslim --with onnxruntime --with safetensors --with sentencepiece --with yaspin --with tqdm --with accelerate \
  python mnn_export.py <MNN>/transformers/llm/export {full|speech} --path <neutts-nano> --export mnn \
  --quant_bit 4 --quant_block 32 --sym --lm_quant_bit 8 --lm_quant_block 32 --dst_path <out>
uv run ... python -I mnn_check.py <neutts-nano> tools/models/neutts/frontend-parity.json <pack>/voice-dave.codes <out> {full|speech} 32
uv run ... --with gguf python -I gguf_check.py <neutts-nano> <same> <same> neutts-nano-Q4_0.gguf 32
```

Phone (`abab.sh`): each cycle runs the four commands below, and cycles repeat for 570 s. `abab2.sh` is the same with `taskset 30`, `-t 2` and the speech pair only, for 240 s.

```sh
taskset f0 ./mnn_tts_bench {full|speech}/config.json ids512.txt 64 4 {0|128261} 2         # rep 1 reported; rep 0 is warm-up
taskset f0 ./llama-bench -m ../{nano-Q4_0|proxy-65600}.gguf -p 512 -n 64 -d 0,512 -r 1 -t 4 -o csv
```

`mnn_tts_bench`:
- uses `Llm::forward` on the first 512 ids of the real prompt;
- greedy-decodes 64 tokens with argmax, with no EOS stop and no sampler;
- runs with MNN config defaults (`precision low`, `memory low`, power normal, so no MNN affinity call).

`llm_bench` was built but not used: it prefills a constant token, stops at EOS, and samples.

## Not done

- **NeuCodec decoder on MNN:** `neucodec-decoder-int8.onnx` converts (`mnnconvert -f ONNX`, 314 MB `.mnn`) but was not run or timed, to keep phone time short and because the backbone result already decides.
- **Untested MNN knobs:** `dynamic_option` 8, KleidiAI on, asym block-64 speed, and a GPU backend.
