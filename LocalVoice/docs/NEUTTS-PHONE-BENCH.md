# NeuTTS Nano on Pixel 7 Pro: benchmark

Measured 2026-10-19 over adb on Pixel 7 Pro (Tensor G2, Android 17 CP3A.261005.002.A1). The phone was on USB power with the screen unlocked on the home screen; no app was involved. The run was shortened on request to a ~10-minute reduced sweep (see "Cut").

## Verdict

- **Real-time on CPU: yes, with a thin margin.** The best config is Q4_0 on CPU with 4 threads, unpinned (what an app gets).
  - Backbone generates 92 tok/s at a 512-token context (RTF 0.54).
  - In NeuTTS streaming, every 0.5 s chunk needs 25 generated tokens (0.27 s) plus a decode of an 81-frame window (0.15 s). That totals 0.42 s per 0.5 s of audio, so **streaming RTF ≈ 0.84**.
- **Vulkan (Mali-G710) is slower than CPU**: tg 51 tok/s, pp 176 tok/s. Do not use it.
- **First audio ≈ 1.1 s (estimated)** from measured parts: prefill 512 tokens 0.71 s + 30 tokens 0.33 s + 31-frame decode 0.09 s. Excluded:
  - model loading (decoder ONNX session load measured at 0.7–1.1 s; GGUF load not measured)
  - G2P/phonemizer time
  - encoding the reference voice (codes are assumed pre-encoded)
- **Neuphonic's claim, checked** (README at commit ac69851f, "Throughput Benchmarking"): Galaxy A25 5G, CPU only, Q4_0, `llama-bench`, 6 threads, 500 prefill + 250 generated tokens, backbone only. Result: Nano 45 tok/s, Air 20 tok/s. The Pixel 7 Pro is about 2× faster on comparable settings.
- **Main risk is sustained thermals.** Each run here lasted about 10 s. CPU temperature went from 46 to 71 °C within one backbone run, and the decoder runs ran at thermal status 1. Long passages were not measured.

## Backbone: `llama-bench`, Q4_0

RTF = 50 / tg tok/s, for the backbone only.

| Backend | Threads (CPU mask) | pp512 @ d512 tok/s | tg @ d512 tok/s | Backbone RTF | Source |
|---|---|---:|---:|---:|---|
| CPU | 4 (unpinned) | 725 | 92.3 (tg64) | 0.54 | reduced sweep, 1 rep |
| CPU | 2 (0xC0 = 2×X1, strict) | 479 | 84.5 (tg64) | 0.59 | reduced sweep, 1 rep |
| CPU | 1 (0x80 = 1×X1, strict) | 256 | 69.8 (tg128) | 0.72 | aborted full sweep, 3 reps |
| Vulkan `-ngl 99` | 4 (unpinned) | 176 | 51.3 (tg64) | 0.98 | reduced sweep, 1 rep |

- The 1-thread X1 run also measured at depth 0: pp512 394 tok/s, tg128 94.9 tok/s.
- Depth 512 models the NeuTTS prompt: reference codes + reference phonemes + target phonemes, about 500 tokens.
- Generation slows as context grows: on 1 thread it fell from 95 tok/s at depth 0 to 70 tok/s at depth 512. Expect lower throughput late in long utterances (context limit 2048).

## Decoder: NeuCodec ONNX int8 on phone, 4 threads (taskset 0xF0)

Input `codes` is int32 `[1,1,F]` and output is `audio` `[1,1,480·F−480]` at 24 kHz. Codes were random. Each row is 2 timed runs after 1 warm-up.

| Frames (role) | Audio | Median ms | Min ms | Decode RTF |
|---|---:|---:|---:|---:|
| 31 (first streaming chunk) | 0.62 s | 86 | 71 | 0.14 |
| 81 (steady-state chunk window: 50 lookback + 25 + 5 lookforward + 1) | 1.62 s | 148 | 129 | 0.09 |

Streaming parameters come from `neutts/neutts.py`: 25 frames per chunk, 5 lookforward, 50 lookback, 1 overlap, hop 480.

Each 0.5 s chunk re-decodes 81 frames, so the effective decode cost is 148 ms per 500 ms of audio (0.30 RTF). That cost adds to the backbone time when both share the same cores.

## Thermal notes

- Readings are live HAL temperatures plus thermal status from `dumpsys thermalservice`. The cached values in that dump are stale and were ignored.
- The phone was warm at the start after another agent's Edge test. The first cool-down wait timed out after 600 s with the BIG cluster at 45 °C, status 0.
- Backbone runs: status 0, BIG cluster 46 → 69–71 °C.
- Decoder runs: status 1 (light), skin 38 °C. The phone was still at status 1 after the runs.
- No cool-down waits were used in the reduced sweep, so the later rows ran on a warmer SoC. The numbers may slightly understate cold-start performance.

## Measured vs estimated

- **Measured on the phone:** every number in both tables, the decoder session load time, and the thermal readings.
- **Estimated by arithmetic from measured parts:**
  - streaming RTF of 0.84 (backbone and decoder added sequentially)
  - first-audio latency of 1.1 s, using pp512 @ d512, which is conservative because a cold prompt starts at depth 0
- **Not measured:** sustained runs longer than about 10 s, end-to-end NeuTTS pipeline with real phonemes and codes, model load time, and the cuts listed below.

## Cut, to keep the run to ~10 minutes

- Q8_0 was not run (its file was downloaded and verified).
- CPU at 6 and 8 threads was not run, and neither was pinned 4-thread X1+A78.
- Depth 0 was only measured for the 1-thread run.
- Only 1 repeat was used for the reduced sweep.
- Decoder: fp32 was not run, int8 at 2 threads was not run, and the 250-frame (5 s) decode was not run.
- There were no cool-down waits between runs.

## Reproducibility

**Model artifacts (Hugging Face, gated: "auto" approval)**

| Repo @ revision | File | SHA-256 |
|---|---|---|
| neuphonic/neutts-nano-q4-gguf @ d775fb6ff95c60a076c68994d9f084473ad9fb86 | neutts-nano-Q4_0.gguf | 85466ca06aeb487e5e8d0263367166e125969e3f4b07245db009aee223702c86 |
| neuphonic/neutts-nano-q8-gguf @ 16705743fc6e00751987b2e3628aa0ea56fc3880 | neutts-nano-Q8_0.gguf | 03ea1e935c259953f811f31ef9e13d8be773683d8db860ab093d4c173a3b9101 |
| neuphonic/neucodec-onnx-decoder-int8 @ d4b449d34211ffe3b5efc57fa464b37393f8ce7f | model.onnx | 3ddd9e56396e6029e0e948ac0255c89c803f981f23dcf4c154f50820bd74a6b3 |
| neuphonic/neucodec-onnx-decoder @ ac01424f74ea25b245132252168e2191b3d859fa | model.onnx (fp32) | 2a43d034d7537a9251c547eba77204c3c77de7f1fb918ed494b6b71ba400ecd1 |

**Toolchain**

- **CPU llama.cpp:** official release b11729 (commit 412a363c655b479279d3050d0d1c67a5733697b4), `llama-b11729-bin-android-arm64.tar.gz`, sha256 03dbe10b9b8005acffb6e73e62521a73b548d2408e251380b0e302f6b65ce172. It auto-selects `libggml-cpu-android_armv8.2_2.so`: G2 has dotprod but no i8mm.
- **Vulkan llama.cpp:** b11729, built with NDK r29 (`android-ndk-r29-windows.zip`, sha256 4f83a1a87ea0d33ae2b43812ce27b768be949bc78acf90b955134d19e3068f1c).
  - Configure flags: `-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-33 -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DGGML_OPENMP=OFF -DGGML_VULKAN=ON`.
  - Headers: Vulkan-Headers v1.4.360 and SPIRV-Headers vulkan-sdk-1.4.357.0. Pass `-I` to the SPIRV include directory and set `SPIRV-Headers_DIR`.
  - `glslc` comes from NDK `shader-tools`.
  - The host `vulkan-shaders-gen` must be built with NDK `clang-cl` and `lld-link`, because MSVC `cl.exe` fails on paths longer than 260 characters.
  - Device report: `Mali-G710 | uma 1 | fp16 1 | int dot 0 | matrix cores none`.
- **Decoder bench:** a minimal C++ CLI (random int32 codes, `Ort::Session::Run` timing) built with NDK r29 clang++ (`--target=aarch64-linux-android33`, `-static-libstdc++`). It links `libonnxruntime.so` from `com.microsoft.onnxruntime:onnxruntime-android:1.31.0` (AAR sha256 72683787a80d0608a3121b967d8e1239aef48286826661c953211626690540a7) with graph optimization set to ALL.

**Commands** (run from `/data/local/tmp/neutts-bench/{cpu,vk,ort}` with `LD_LIBRARY_PATH=.`; that directory was deleted afterwards)

```sh
./llama-bench -m nano-Q4_0.gguf -p 512 -n 64 -d 512 -r 1 -t 4
./llama-bench -m nano-Q4_0.gguf -p 512 -n 64 -d 512 -r 1 -t 2 -C 0xC0 --cpu-strict 1
./llama-bench -m nano-Q4_0.gguf -p 512 -n 64 -d 512 -r 1 -t 4 -ngl 99            # Vulkan build
./llama-bench -m nano-Q4_0.gguf -p 512 -n 128 -d 0,512 -r 3 -t 1 -C 0x80 --cpu-strict 1
taskset f0 ./dec_bench dec-int8.onnx {31|81} 4 2                                  # frames threads iters
```
