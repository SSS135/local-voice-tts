# NeuTTS Nano on Pixel 7 Pro: benchmark

Measured 2026-10-19 over adb on Pixel 7 Pro (Tensor G2, Android 17 CP3A.261005.002.A1). The phone was on USB power with the screen unlocked on the home screen; no app was involved. The run was shortened on request to a ~10-minute reduced sweep (see "Cut"). The 2026-10-20 optimization round is in the next section; the sections after it are the 2026-10-19 baseline.

## Optimization round (2026-10-20)

Lever status is in `docs/NEUTTS-OPTIMIZATION.md` ("Status after the 2026-10-20 round"). Runs used `NeuTtsCli` unpinned, which is the realistic setting: the bound service sits in `top-app` on CPUs 0-7. Every run used threads 2/4/3, voice dave, and `passage.txt` (777 chars). In the tables, *base* is commit b8ce35d. *head* adds the speech-only head. *best* is head plus `streamChunk=50 streamLookback=50 segmentLimit=250`.

**Headline: sustained, warm phone.** Wired charger. Rounds ran back to back with no pauses. Thermal status 2, skin 41–43 °C, BIG 58–66 °C. policy4 (A78) ran at 1.0–1.2 GHz and policy6 (X1) at ≈ 0.98 GHz, against maxima of 2.35 and 2.85 GHz.

| Config (2 interleaved rounds) | RTF | First audio | Stall per 37 s | Backbone `gen` tok/s | WER (single sample each) |
|---|---|---|---|---|---|
| base | 1.773 / 1.751 | 2.73 / 2.72 s | 27.2 / 27.4 s | 41.2 / 39.9 | 0.014 / 0.014 |
| head | 1.607 / 1.639 | 2.93 / 3.01 s | 21.9 / 21.8 s | 59.3 / 56.9 | 0.007 / 0.000 |
| best | 1.424 / 1.415 | 2.81 / 3.02 s | 14.4 / 13.7 s | (as head) | 0.021 / 0.057 |

**5-minute sustained run of *best*.** `passage.txt` ×8 (287.6 s of audio). The phone started at status 1, skin 39 °C. Overall: RTF 1.238, stall 67.8 s, first audio 1.69 s, peak VmHWM 1,003 MB.

| Minute | 0 | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|---|
| RTF | 0.96 | 1.16 | 1.36 | 1.35 | 1.32 | 1.35 |
| Pipeline codes/s | 66 | 54 | 47 | 47 | 48 | 47 |
| Prefill per segment | 1.06 s | 1.51 s | 2.04 s | 1.96 s | 1.93 s | 1.96 s |
| Status / skin | 1 / 40–43 °C | 2 / 43 °C | 2 / 42 °C | 2 / 42 °C | 2 / 42 °C | 2 / 42 °C |
| A78 / X1 clock (GHz) | 1.7–2.1 / 1.4–1.6 | 1.2–1.3 / 1.0–1.6 | 1.0–1.2 / 0.98 | 1.0 / 0.98 | 1.2 / 0.85–0.98 | 0.7–1.2 / 0.98 |

- **It does not keep up with real time once warm.** RTF levels off at ≈ 1.35 after 2 minutes.
- Warm *best*, per 37 s of audio: prefill 10.5 s (6 segments × ≈ 1.75 s), generation 39 s at 48 codes/s (50 are needed for real time), decode 29.5 s (overlapped).
- Peak VmHWM was 971–981 MB in every `say` run (ART + llama + ORT, CLI process). No configuration changed memory.

**Earlier numbers, taken before the switch to a wired charger.** The phone was on a **wireless charger**, at status 1–3 and skin 40–45 °C, so these are heat-affected.
- `llama-bench` b11729, `-p 512 -n 64 -d 512`. tg64: 84.3 (t2) and 90.2 (t4) on the shipped GGUF, against 116.5 and 128.5 on a timing proxy with the vocabulary cut to 65,600 rows (×1.38 / ×1.43). pp512 was unchanged.
- `llama-batched-bench` t2, npp 600, ntg 64: S_TG for B = 1/2/4 is 52.5/78.2/145.5 on the shipped GGUF and 71.4/96.0/163.9 on the proxy. A batch of 2 therefore costs ≈ 1.34–1.39× a single token.
- CLI `gen 300 2/4`: base 72.3/74.1 tok/s, head 99.1/99.8 tok/s. `head` exactness: max \|Δlogit\| 0 over 3 seeds × 150 steps.
- NeuCodec through the app's ORT 1.28.2, 3 threads, `taskset 3f`. F = 31/56/81/106/131 took 131/219/322/415/535 ms (≈ 4 ms per frame, near-zero fixed cost).
  - `allow_spinning=1`: 292/420/573/754/970 ms.
  - `use_device_allocator_for_initializers=1`: same times, and RSS after load was 340 MB either way.
  - Unpinned F=81 profile: 197 ms run, of which 191 ms is kernels. DynamicQuantizeMatMul 42%, Conv 33%, ConvTranspose 4%.
- End-to-end, with the phone heating from status 2 to 3 across the runs: base 1.268, head 1.189, chunk 50/lookback 25 1.418, chunk 50/lookback 50 1.466. The order effect dominates these numbers; they are superseded by the warm table.

**Item 7 (pure Q4_0).** Interleaved on a semi-warm phone (status 1, skin 39–42 °C):
- shipped GGUF: 102.0/88.6/85.5 tok/s, VmHWM 940.4–940.8 MB;
- pure Q4_0: 95.1/85.8/85.6 tok/s, VmHWM 943.0–943.2 MB;
- WER with fixed seeds: dave seed 1000 0.007 vs 0.036, jo seed 2000 0.064 vs 0.071.
- No speed gain.

WER uses faster-whisper small.en on single samples. Most differences are ASR normalization, such as "one hundred and twelve" → "112" or "harbour" → "harbor". **All quality figures here are provisional** until the protocol in `docs/TTS-QUANT-QUALITY.md` is run.

## Verdict (2026-10-19 baseline)

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
