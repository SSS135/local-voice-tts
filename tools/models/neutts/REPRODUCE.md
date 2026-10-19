# NeuTTS Nano (English): runtime pack and native runtime

Catalog `neutts-nano-en`, backend `llama-neutts` (`NeuTtsBackend`), release `neutts-nano-model-v1`. Pins: `runtime-manifest.json` (must match `app/src/main/resources/org/localvoice/tts/models.json`). Measurements: `device-results.json`.

## Pack

```powershell
.\tools\models\neutts\download-inputs.ps1     # pinned inputs -> build/; gated HF files need $env:HF_TOKEN or ~/.cache/huggingface/token
uv run --no-project --with numpy python -I tools/models/neutts/build_pack.py   # build/pack/ + runtime-manifest.json + build/catalog-files.json
```

- GGUF backbone and int8 NeuCodec decoder: unchanged upstream files (`source-input-manifest.json`).
- Voices: `samples/{dave,jo,emily,paul,sophie,steven}.pt` from neutts @ ac69851f (the README's English references) read without torch and written as integer text; transcripts copied. Locales by a CommonAccent ECAPA classifier (speechbrain `Jzuluaga/accent-id-commonaccent_ecapa`): dave, sophie, steven en-GB (clear); jo, emily, paul en-US (medium; jo/paul also score Australian/NZ). Not listened to.
- eSpeak data: `phontab phonindex phondata intonations en_dict lang/gmw/en-US` from the neutts 1.4.1 wheel (eSpeak NG 69bfd1ef, the commit neutts' CMakeLists builds); the en-us voice needs nothing else.
- Release upload: `gh release create neutts-nano-model-v1 build/pack/*` with attribution notes; verify anonymous 200 + SHA-256 of every asset.

## Native runtime (`app/src/main/cpp`, built by Gradle `externalNativeBuild`)

`libneutts_jni.so` per ABI (arm64-v8a, x86_64): NDK r29 (`ndkVersion 29.0.14206865`), CMake 3.22.1, `c++_static`, Release.
- llama.cpp b11729 (412a363c) static, CPU only, no OpenMP; arm64 `-march=armv8.2-a+dotprod+fp16`, x86_64 AVX2/FMA/F16C/BMI2. JNI `open` refuses other CPUs (no SIGILL).
- eSpeak NG 69bfd1ef library sources compiled directly (its CMake project git-clones libsonic).
- NeuCodec through the sherpa-onnx AAR's own `libonnxruntime.so` (C API, headers `third_party/onnxruntime-c-api-1.28.2`, symbol version `VERS_1.28.2`); only that copy ships.
- Sources: `third_party/native-source/*.zip`, pinned in `source-manifest.json` (`fetch-sources.ps1`); CMake falls back to the pinned URL + SHA-256 when a zip is absent.

JNI (`NeuTtsNative`): `open(gguf, decoder, espeakData, voice, threads, batchThreads, decoderThreads, ctx)`, `phonemes(utf8)`, `prefill(handle, utf8Prompt, seed)` (reuses the longest cached token prefix), `next(handle, stopToken)`, `cancel` (llama abort callback), `decode(codes)`, `close`.

## Fidelity to neutts ac69851f

Same as upstream: prompt template (`_ggml_prompt`, phoneme input), phonemizer config (`en-us`, stress, punctuation preserved, flags removed; ported from phonemizer 3.4.0), sampling (llama-cpp-python chain: top-k 50, top-p 0.95, min-p 0.05, temperature 1.0), streaming (25 codes/chunk, 5 lookforward, 50 lookback, 1 overlap, linear overlap-add), 24 kHz, 2048 context.

Deviations:
- Text is cut at the punctuation match position; upstream cuts at the mark's first textual occurrence and drops the words after a decimal ("4.5 and 6,000 people" vanished). Fixture case "Numbers: …".
- A sampled non-speech token is skipped (upstream counts it as a frame position).
- Restored phoneme lines are all kept (upstream uses only the first).
- No Perth watermark. Random seed per call. KV prefix reuse covers only `user: …<|TEXT_PROMPT_START|>{reference phonemes}`; the reference codes follow the request text in the template, so ~370–650 code tokens are re-evaluated per segment.

## Checks

- `NeuTtsFrontendTest` (`gradlew test`): phonemes vs `frontend-parity.json` (regenerate: `uv run --no-project --with phonemizer==3.4.0 python -I tools/models/neutts/phoneme_parity.py`, Windows, uses the wheel's `espeak-ng.dll`); prompt string; reference parsing; incremental streaming equals a literal port of upstream's loop.
- Device CLI (no install): `.\gradlew.bat :app:assembleDebug; .\tools\models\neutts\build-cli.ps1 -Abi arm64-v8a`, push `build/cli/<abi>/*` and the pack (laid out by `runtime-manifest.json` paths) to `/data/local/tmp/<dir>/`, then
  `cd /data/local/tmp/<dir> && LD_LIBRARY_PATH=. CLASSPATH=neutts-cli.dex app_process -Djava.library.path=$PWD /system/bin org.localvoice.tts.NeuTtsCli say m 0 passage.txt out.wav 2/4/3` (`phon m sentences.txt` prints raw eSpeak output; `gen m 200 4/4` times the backbone alone). Delete the directory afterwards.
