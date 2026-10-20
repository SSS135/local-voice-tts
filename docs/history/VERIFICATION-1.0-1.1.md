# Verification records 1.0–1.1.1

Moved verbatim from the 1.1.1 `README.md` on 2026-10-20 (relative links re-based only). These are historical records; current behaviour is in [ARCHITECTURE.md](../ARCHITECTURE.md) and build/test instructions in [DEVELOPMENT.md](../DEVELOPMENT.md).

## 1.1.1 release note

**LocalVoice 1.1.1, version code 3:** this focused patch removes duplicate download hashing, preserves stored-byte verification and atomic installation, coalesces whole-job progress, and avoids rewriting unchanged UI card text. Final-candidate runtime checks and the bounded descriptive comparison are summarized in [RELEASE-1.1.1.md](RELEASE-1.1.1.md), with historical profiles in [VERIFICATION-PROFILE.md](VERIFICATION-PROFILE.md). Mixed full-install timings must not be described as a universal speedup. Packaging and delivery-file hashes are separate; see [SOURCE-PACKAGING.md](../SOURCE-PACKAGING.md).

The historical **1.1.0/code 2** checks below cover four local model families, download/import cancellation and retry, tiny-file repair, online discovery/revocation, Pocket smoke, the layout matrix and VN Reader System TTS preview. Those earlier suites retain their actual APK lineage; they are not relabelled as exhaustive tests of 1.1.1. Model/catalog functionality is unchanged by this patch.

## Install and engine behaviour notes as of 1.1.1

Install your APK, open LocalVoice, download a local model and keep the app foreground until installation completes. Select a voice in the app, open Android's text-to-speech settings and choose LocalVoice as the preferred engine. The separate `probe` APK is a test client, not required for normal use.

- Kokoro v1 English provides 28 US/UK voices after model installation.
- Supertonic 3 provides ten styles in English and Russian after model installation.
- PocketTTS English (`pocket-en-v2`) uses the project's INT8 ONNX export of Kyutai's `english_2026-09_2` checkpoint (release `pocket-en-model-v2`; recipe [tools/models/pocket-en/REPRODUCE.md](../../tools/models/pocket-en/REPRODUCE.md)) and one fixed Alba MacKenna voice (`en-GB`). Model and voice attribution is CC-BY-4.0. The previous `pocket-en-alba` install is deleted automatically on first start.
- Shtorm Russian uses the pinned fast FP32 ONNX conversion and one VCTK p228 reference voice. **Download** fetches its fourteen pinned files from the [`shtorm-model-v1`](https://github.com/SSS135/local-voice-tts/releases/tag/shtorm-model-v1) GitHub release (license and attribution in the release notes). Android Russian PCM generation has passed.
- Silero v5.6 Russian (`silero-ru`): five voices (Xenia, Aidar, Baya, Kseniya, Eugene), 48 kHz, Silero's own stress/ё/homograph frontend ported to Java. **Download** fetches six pinned files (152.9 MiB) from the [`silero-ru-model-v1`](https://github.com/SSS135/local-voice-tts/releases/tag/silero-ru-model-v1) release. **CC BY-NC-SA 4.0: non-commercial use only.** Integers are spoken as nominative cardinals; Latin letters, SSML and emphasis marks are dropped. Export and validation: [tools/models/silero-ru/REPRODUCE.md](../../tools/models/silero-ru/REPRODUCE.md).
- NeuTTS Nano English (`neutts-nano-en`): six voices cloned from Neuphonic's reference samples (Dave, Sophie, Steven `en-GB`; Jo, Emily, Paul `en-US`), 24 kHz, Q4_0 GGUF backbone on llama.cpp plus the int8 NeuCodec decoder, eSpeak NG phonemes. **Download** fetches 22 pinned files (484 MiB) from the [`neutts-nano-model-v1`](https://github.com/SSS135/local-voice-tts/releases/tag/neutts-nano-model-v1) release. **NeuTTS Open License 1.0**: paid Neuphonic licence above its revenue threshold. Heavy: on a Pixel 7 Pro CPU about real time (RTF 1.05–1.18 incl. per-segment prompt evaluation), first audio ~1.7 s, the SoC warms quickly. Recipe and deviations: [tools/models/neutts/REPRODUCE.md](../../tools/models/neutts/REPRODUCE.md).
- Microsoft Edge voices require explicit online enablement. The bundled metadata snapshot contains 322 voices across 142 locales; explicit refresh replaces it only after complete validation. Speech text is sent to Microsoft only for a requested online voice. Local failures do not fall back to the network. The online endpoint is unofficial and can change.

Downloads are process-bound: after a killed app, use Retry. Completed verified files are reused; a partial file starts again. No background completion guarantee is made. Online voices can be disabled immediately even while a local installation occupies the storage worker. Kokoro Russian remains unsupported by the English adapter.

Downloads show **Downloading and checking** with cumulative package bytes while connecting, copying and checking completed files. Final whole-package verification and finalization remain visible phases. Cancel remains available throughout a running job; incomplete staging is never advertised as an installed voice.

The release assets are byte-identical to the members of the Shtorm package `LocalVoice-Shtorm-RU-fast-v1.zip`, exactly 440,049,147 bytes with SHA-256 `8b9892ae5f14539535d8d803a0161e4382ce1e590f4661efc7c180acc139ae88`. The app downloads the fourteen pinned runtime/license files individually and validates each before atomic activation; there is no ZIP import path. The source archive excludes this model pack and all weights.

Shtorm expands isolated signed cardinal integers from −999,999,999 through 999,999,999 using an app-owned Russian formatter. It rejects leading-zero numbers, decimal/date/time formats and digits attached to identifiers. This is nominative cardinal wording, not grammatical inflection. Expansion happens before bounded prompt/token chunking. Unsupported numeric formats report an error rather than being silently split and spoken incorrectly.

The local baseline uses two CPU threads, eight Supertonic generation steps and text chunks of at most 180 characters. Adapter configuration supports `numThreads` (clamped 1..4) and Supertonic `numSteps` (clamped 2..16); these remain catalog settings. The manager's speed slider applies a 0.5×–2× multiplier to the caller's rate once, with the final rate clamped to that range. The service takes each adapter's actual sample rate; Kokoro uses 24 kHz and the tested Supertonic runtime reported 44.1 kHz. Do not hardcode a shared sample rate in a client.

Stopping Supertonic suppresses obsolete PCM promptly but native computation may finish its current bounded chunk before unloading/switching. Edge validates and fully decodes a bounded MP3 turn before committing PCM. If offline, disconnected or stalled, it keeps retrying the same uncommitted text chunk with bounded backoff until recovery or cancellation; it does not skip the chunk, fall back or report success because an attempt failed. Fatal invalid-voice/authorization/configuration errors still surface. Framework Stop/replacement and changes to the saved voice/online capability cancel pending attempts. See [ARCHITECTURE.md](../ARCHITECTURE.md) for the contract, engine extension recipe and precise cancellation/privacy limits.

Pending retry depends on the requesting app remaining alive and keeping the request open. Following `QUEUE_ADD` text waits behind the pending chunk in order. Client Stop/shutdown, process death or OEM cleanup can end the request. Unchanged VN Reader automatic narration imposes a ten-minute timeout on each prepared chunk and then stops/reports a problem; LocalVoice cannot override that caller limit. Its engine initialization timeout is separate. No universal audiobook recovery across a lost client/process or that ten-minute narration limit is claimed.

Clients that do not pick a voice by name get the voice selected in the manager whenever its language matches the requested locale; the region only ranks other voices. Clients that pick a voice by name get exactly that voice.

Modern clients discover enabled online voices through `getVoices()` (marked network-required); `isLanguageAvailable()` counts an online voice's language only when that voice is selected in Local Voice. The legacy `ACTION_CHECK_TTS_DATA` intent checks **installed local resources only**. With Edge enabled and zero local models, it returns `CHECK_VOICE_DATA_FAIL`, so a legacy client that gates solely on that result may request a local-model installation even though modern online voice discovery works.

## Verification as of 1.1.1

Run all host (JVM) checks; needs the AAR from `fetch-runtime.ps1` and `ANDROID_HOME` or `local.properties`:

```powershell
.\gradlew.bat test        # ./gradlew test on Linux/macOS
```

Checks are `main()` programs with a thin JUnit `check()` entry: `app/src/test/java` (Android unit tests, real `org.json` via `com.vaadin.external.google:android-json`) and `hosttest/` (actual `ModelStore` compiled against stub `android.*`/`Catalog`; its production file list is in `hosttest/build.gradle`). Each check class runs in its own JVM. `ShtormBpeCheck` is skipped unless the untracked `tools/models/shtorm/onnx` exists. Reports: `app/build/reports/tests/`, `hosttest/build/reports/tests/`.

The backend contract compiles actual production backend sources and uses injected fakes without loading native models. The Edge retry contract fault-injects transient/offline recovery, cancellation, real MP3/PCM staging limits and fatal errors against the production pure-Java helpers. The storage test uses assertions against actual disk helpers and catalog metadata. These suites do not prove Android API-26 runtime compatibility, JNI audio quality, real online recovery or framework callback behavior. The [test-only WebSocket fixture](../../tests/edge-fault-fixture/README.md) is used separately for emulator transport/codec fault injection.

Storage profiling methodology, raw evidence locations and current runtime-measurement limits are recorded in [VERIFICATION-PROFILE.md](VERIFICATION-PROFILE.md).
Recorded automated results: **14 backend contract checks**, **26 Edge retry/audio fault checks**, protocol/`Retry-After` checks, storage self-check and **two host fixture tests passed**. Independent review reports and detailed evidence remain in the local workspace; private review reports are excluded from source packaging and repository publishing.

The 1.1 Android storage comparison used the same installed model files. One hundred main-thread installed-state calls—all asserting true—took 11,149 → 19 ms for Kokoro and 2,638 → 20 ms for Supertonic. Cached retry hashed 345,463,864 → 172,856,837 bytes for Kokoro and 290,621,550 → 145,310,775 for Supertonic, preserving complete first verification and conservative rechecks. These are emulator storage observations; boot/JIT/filesystem-cache differences confound wall-time ratios. Process-wide counters can include concurrent background refresh. See [VERIFICATION-PROFILE.md](VERIFICATION-PROFILE.md) for exact counts, phase observations and remaining acceptance work.

The final 1.1.0 APK is 69,584,186 bytes with SHA-256 `1da7f5dbc38c95b0cc570275572570c26c8e305c7f3f3f7cc5907ffc89ef700c`. It preserves the original certificate SHA-256 `d7e7cbdd26574111fbcfb333b3c4bcb2c75e000df4551fab3fe5022282183ba8`; no new signing identity or backup was created.

Acceptance lineage remains explicit: the exhaustive native/offline suite ran on `d1291ab6f566b6fda4f1b76f50ddccc905f823e7c2cae7c27813b3df6cdbb591`. The next candidate, `8ebd07c0f4da705d00745bc1e7410b6ad6233ee9e0ec8e55f59fe32474fc170a`, changed only the local data-check activity to inspect resources on a worker. Final `1da7…` changed only bottom-navigation text from 14 to 12 sp. Its exact-artifact offline Pocket smoke and cold data-check intent passed; the full earlier suite is not relabeled as rerun on the final bytes.

The exact 8eb candidate then passed both offline Pocket smokes: English produced 55,680 frames/111,360 PCM bytes and Shtorm Russian 69,120 frames/138,240 bytes, both at 24 kHz with successful done and no error. Production Edge English and Russian also completed on that candidate; French had completed in the preceding run.

On final `1da7…`, offline Pocket English produced 57,600 frames and Shtorm Russian 65,280 frames at 24 kHz, both done without error. The cold public data-check event at 513 ms reported `eng-USA`, `eng-GBR`, `rus-RUS`, missing `[]`.

| 1.1 emulator acceptance | Verified scope |
| --- | --- |
| Settled active network `None` | Kokoro English and PocketTTS English/Shtorm Russian produced nonempty 24 kHz PCM; Supertonic English/Russian produced 44.1 kHz PCM. |
| Pocket rate 0.75 / 1.25 | Same test text produced 3.200 / 1.728 seconds of audio. This establishes different output durations, not an exact inverse-duration ratio for stochastic generation. |
| Active PCM Stop, both Pocket adapters | Stop after 36,864 PCM bytes, with no later PCM or successful done observed; request replacement and process restart also passed. Native computation/unload can still wait for its bounded chunk. |
| Real install workflows | The 126,176,567-byte English pack passed download cancellation/cache retry. The 440,049,147-byte Shtorm SAF import passed cancellation near 32%, explicit re-selection/retry and installation. |
| Integrity and repair | A same-size English README mutation with restored mtime invalidated its receipt/voice. Explicit repair fetched only the 5,251-byte README, with no automatic background download. |
| Online discovery and revocation | Cold headless discovery exposed 372 voices, including 322 network voices. A pending offline Edge request remained pending until explicit Disable, then reported error without PCM or successful done. Restoring Wi-Fi did not resume it; disabled discovery contained 50 local/zero network voices. |
| Production online speech | English/Russian completed on the 8eb candidate; French `fr-FR-DeniseNeural` produced nonempty 24 kHz PCM in the preceding run. |
| UI layout and interaction | Portrait discovery/filter, exact Alba preview isolation, saved voice selection, landscape Listen/IME, dark theme, About and full licenses passed. At 360 dp width/font scale 1.5, navigation remained one line at 56 dp; large-font Details used three stacked 48 dp actions. |
| Global and caller rate | UI 1.5× combined with caller 1.25× once: Supertonic output duration was 1.490816 s against 2.795283 s at 1×, matching effective 1.875×. The saved multiplier was restored to 1.0. |
| Unchanged VN Reader | Version 3.4.4 used its System TTS provider with saved `pocket-en-alba:alba`; service first PCM and done were observed. VN's APK remained unchanged and its preferences were restored byte-for-byte. |

The Edge callback log's 236,398 ms timestamp includes Android freezing the client; it is not engine cancellation latency. The live pending request was observed for about 160 seconds before UI revocation. Sonic pitch preservation is covered by a deterministic host signal test; Android WAV/rate checks do not establish subjective voice quality or physical-device speed. Test display/default-engine/network settings were restored after acceptance. There was no subjective listening evaluation or separate VN-specific WAV recording.

Existing D129 public-client observations below are separate invocations, **not controlled cold/warm pairs**. PSS is a whole-process post-synthesis snapshot, including runtime/allocator state after model switching, rather than isolated model memory or a peak allocation:

| Model request | Request to first PCM | Process PSS, KiB |
| --- | ---: | ---: |
| Kokoro English | 8,065 ms | 323,277 |
| Supertonic English / Russian | 3,272 / 2,536 ms | 273,164 / 269,660 |
| Pocket English | 2,928 ms | 492,101 |
| Shtorm Russian | 7,168 ms | 1,181,275 |

These API-36 x86_64 emulator observations do not predict Pixel/Huawei latency, battery, thermal behavior or perceived quality. No new formal Pocket cold/warm pair was measured. Verify delivered files against the separate delivery JSON / `SHA256SUMS`, which carry archive/file hashes after packaging.

The following measurements describe the earlier **1.0 baseline**.

The frozen 1.0 signed production APK has SHA-256 `e7e6945e32454e2ce2321671cfa57e51c9c87c194ae3bc36aae8e2d8991e9538`; its installed APK checksum matched, and the original signing certificate was preserved. Verified through the Android system client on the API-36 x86_64 emulator:

| Check | Verified result |
| --- | --- |
| Production Microsoft Edge English/Russian | Nonempty 24 kHz audio from the actual service. |
| Local speech with settled active network `None` | Supertonic English/Russian at 44.1 kHz and Kokoro at 24 kHz succeeded. |
| Installed voice/data discovery | 48 local voices; check-data passed for English US/UK and Russian, with no missing installed language. |
| Russian rate 0.75 / 1.25 | Audio duration 3.960 / 2.376 seconds. |
| Process restart | Local system-client speech succeeded after restart. |
| Stop during PCM/backpressure | Stop after 49,152 PCM bytes; call took 453 ms under two-emulator load, with no late PCM, done or error events. |

Genuine cold/warm public-API measurements used the same text, voice and client binding; the second request began after the first `onDone`, with the same process/backend retained and no queue wait. Request-to-first-PCM was Kokoro **9,802 / 4,921 ms** and Supertonic **5,951 / 2,529 ms**. These are emulator/host observations under two concurrent emulators, not phone measurements or guaranteed latencies.

Separate debug-fixture tests exercised the real Android decoder and public client against a controlled WebSocket server: three ordered chunks after partial close, truncated MP3 and a stall matched the baseline **152,064 PCM bytes bit-for-bit**; thirteen HTTP 503 failures lasting over four minutes recovered the same pending request. Backoff/in-flight Stop calls took 7/15 ms; voice replacement emitted no old-request PCM. Cross-client manager cancellation reported `ERROR_SYNTHESIS` with no PCM or successful done; the probe observed two error notifications. No actual HTTP 403 fixture was run: fatal authorization behavior is covered by source mapping review and JVM fatal-error tests. These fixture results are distinct from production Microsoft availability and the frozen release's production checks.

An emulator native-generation comparison held text/audio size constant (123,272 output samples): Supertonic two-thread runs took 1,343/1,348 ms, versus four-thread runs at 1,451/1,411 ms; process PSS was approximately 283–299 MB. A separate Kokoro comparison with fixed text/speaker took 4,433/4,355 ms with two threads versus 4,151/4,024 ms with four, at approximately 355–393 MB PSS. Four threads helped Kokoro modestly on that host but did not help Supertonic. Two remains the conservative shared CPU baseline; this is not a measured phone thermal optimum. Eight generation steps remains the chosen baseline. No Pixel/Huawei physical-device result is claimed.
