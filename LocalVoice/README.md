# LocalVoice

A separate Android system text-to-speech engine for local English/Russian speech and explicitly enabled Microsoft online voices. Package: `org.localvoice.tts`. Android minimum SDK 26; target/compile SDK 36. Local CPU inference supports ARM64 phones, including the intended Pixel 7 Pro and Huawei Pura 70 targets, without Google Play Services or an accelerator service. Physical-phone speed, battery and thermal behavior have not been measured.

The application is GPL-3.0; see [LICENSE](LICENSE). The Microsoft Edge protocol adaptation from VN Reader and its upstream LGPL component retain their separate notices in [THIRD_PARTY_EDGE_NOTICES.txt](legal/THIRD_PARTY_EDGE_NOTICES.txt) and [EDGE_TTS_LICENSE.txt](legal/EDGE_TTS_LICENSE.txt). Model licenses are independent: Kokoro weights Apache-2.0, its eSpeak frontend GPL-3.0, Supertonic 3 weights OpenRAIL-M, and the Pocket/Shtorm packages CC-BY-4.0 with their conversion/reference-voice provenance. The source archive includes no model weights. The in-app catalog discloses licenses before download/use. VN Reader is not required or modified by this application.

**LocalVoice 1.1.1, version code 3:** this focused patch removes duplicate download hashing, preserves stored-byte verification and atomic installation, coalesces whole-job progress, and avoids rewriting unchanged UI card text. Final-candidate runtime checks and the bounded descriptive comparison are summarized in [RELEASE-1.1.1.md](docs/history/RELEASE-1.1.1.md), with historical profiles in [VERIFICATION-PROFILE.md](docs/history/VERIFICATION-PROFILE.md). Mixed full-install timings must not be described as a universal speedup. Packaging and delivery-file hashes are separate; see [SOURCE-PACKAGING.md](docs/SOURCE-PACKAGING.md).

The historical **1.1.0/code 2** checks below cover four local model families, download/import cancellation and retry, tiny-file repair, online discovery/revocation, Pocket smoke, the layout matrix and VN Reader System TTS preview. Those earlier suites retain their actual APK lineage; they are not relabelled as exhaustive tests of 1.1.1. Model/catalog functionality is unchanged by this patch.

## Build on Windows

Install a JDK 17 or newer, Android SDK Platform 36 and its build tools using Android Studio/SDK Manager. Review and accept SDK licenses yourself. The included build script does not install an SDK, accept licenses or create an emulator.

Set `JAVA_HOME` and `ANDROID_HOME`, or pass paths explicitly:

```powershell
.\build.ps1 -JavaHome 'C:\path\to\jdk' -AndroidSdk 'C:\path\to\Android\Sdk' -CheckOnly
.\build.ps1 -JavaHome 'C:\path\to\jdk' -AndroidSdk 'C:\path\to\Android\Sdk'
```

The default tasks are `:app:assembleDebug` and `:probe:assembleDebug`. Outputs are `app/build/outputs/apk/debug/app-debug.apk` and `probe/build/outputs/apk/debug/probe-debug.apk`. `build.ps1` discovers Android Studio's JDK, a `javac` on PATH, and the usual per-user Android SDK when environment overrides are absent. A private `local.properties` file with a different `sdk.dir` must be updated or removed before using a new SDK path; it is excluded from the source package.

The Gradle wrapper selects **9.5.1**. Its first invocation downloads the pinned distribution from Gradle, checks `distributionSha256Sum`, and resolves Maven dependencies. An existing installation can be selected with `-GradleExecutable 'C:\path\to\gradle-9.5.1\bin\gradle.bat'`. Use `-GradleUserHome` to choose a cache and `-Offline` only after that cache is populated. This build path contains no task-specific VN Reader or SDK cache path.

```powershell
.\build.ps1 -Tasks ':app:assembleDebug', ':probe:assembleDebug'
.\build.ps1 -Tasks ':app:assembleRelease'
```

Pinned application dependencies are Android Gradle Plugin 9.2.0, Kotlin stdlib 2.3.21, OkHttp 5.4.0 and the supplied sherpa-onnx 1.13.8 AAR; tracing is constrained to 1.2.0. The root build script also selects Kotlin Gradle plugin 2.3.21 where AGP requests 2.2.10. sherpa AAR SHA-256: `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`.

The copied standard wrapper bootstrap jar has SHA-256 `d3b261c2820e9e3d8d639ed084900f11f4a86050a8f83342ade7b6bc9b0d2bdd`, listed for Gradle's 8.6 bootstrap; it launches the pinned 9.5.1 distribution. The distribution SHA-256 is `bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f`. Both were checked against the [official Gradle checksum reference](https://gradle.org/release-checksums/). Wrapper scripts/jar retain Apache-2.0 terms in [GRADLE_WRAPPER_LICENSE.txt](legal/GRADLE_WRAPPER_LICENSE.txt).

## Signing and updates

A clean source checkout builds debug with Android's normal debug key and release **unsigned**. To sign your own release, copy [signing.example.ps1](signing.example.ps1) to a private `signing.local.ps1`, edit the external keystore path and dot-source it before the release build. The example prompts for the store password without echoing it. Gradle reads `LOCAL_VOICE_KEYSTORE`, `LOCAL_VOICE_STORE_PASSWORD`, optional `LOCAL_VOICE_KEY_ALIAS` (default `localvoice`) and optional `LOCAL_VOICE_KEY_PASSWORD` (defaults to the store password). An explicitly supplied missing keystore fails instead of silently signing with another key.

If you need a new independent signing identity, create the private destination directory yourself and run `keytool` interactively; passwords are prompted rather than put on the command line. Back up the resulting keystore/password separately. This new key cannot update an APK signed by the original delivered key.

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -keystore 'C:\private\LocalVoice\own-release.jks' -alias localvoice -keyalg RSA -keysize 3072 -validity 10000
```

```powershell
. .\signing.local.ps1
.\build.ps1 -Tasks ':app:assembleRelease'
Remove-Item Env:LOCAL_VOICE_STORE_PASSWORD -ErrorAction SilentlyContinue
Remove-Item Env:LOCAL_VOICE_KEY_PASSWORD -ErrorAction SilentlyContinue
```

The original delivered application's signing key is a **separate private backup**, not part of this source archive. Keep its keystore and password in durable private storage outside the source tree. Restoring that original key lets an owner sign compatible updates. Generating a new key or using the normal debug key cannot update an already installed APK signed by the original key; Android requires the matching signing identity. Do not replace or discard the original backup while testing a clean source build. The developer's existing private `signing/local-voice.keystore` path remains supported when its password environment variable is set, but that folder is excluded from shared source.

## Install and use

Install your APK, open LocalVoice, download a local model and keep the app foreground until installation completes. Select a voice in the app, open Android's text-to-speech settings and choose LocalVoice as the preferred engine. The separate `probe` APK is a test client, not required for normal use.

- Kokoro v1 English provides 28 US/UK voices after model installation.
- Supertonic 3 provides ten styles in English and Russian after model installation.
- PocketTTS English uses the July 2026 INT8 export of the older `tts_b6369a24` checkpoint and one fixed Alba MacKenna voice (`en-GB`). Model and voice attribution is CC-BY-4.0.
- Shtorm Russian uses the pinned fast FP32 ONNX conversion and one VCTK p228 reference voice. **Download** fetches its fourteen pinned files from the [`shtorm-model-v1`](https://github.com/SSS135/local-voice-tts/releases/tag/shtorm-model-v1) GitHub release (license and attribution in the release notes). Android Russian PCM generation has passed.
- Microsoft Edge voices require explicit online enablement. The bundled metadata snapshot contains 322 voices across 142 locales; explicit refresh replaces it only after complete validation. Speech text is sent to Microsoft only for a requested online voice. Local failures do not fall back to the network. The online endpoint is unofficial and can change.

Downloads are process-bound: after a killed app, use Retry. Completed verified files are reused; a partial file starts again. No background completion guarantee is made. Online voices can be disabled immediately even while a local installation occupies the storage worker. Kokoro Russian remains unsupported by the English adapter.

Downloads show **Downloading and checking** with cumulative package bytes while connecting, copying and checking completed files. Final whole-package verification and finalization remain visible phases. Cancel remains available throughout a running job; incomplete staging is never advertised as an installed voice.

The release assets are byte-identical to the members of the Shtorm package `LocalVoice-Shtorm-RU-fast-v1.zip`, exactly 440,049,147 bytes with SHA-256 `8b9892ae5f14539535d8d803a0161e4382ce1e590f4661efc7c180acc139ae88`. The app downloads the fourteen pinned runtime/license files individually and validates each before atomic activation; there is no ZIP import path. The source archive excludes this model pack and all weights.

Shtorm expands isolated signed cardinal integers from −999,999,999 through 999,999,999 using an app-owned Russian formatter. It rejects leading-zero numbers, decimal/date/time formats and digits attached to identifiers. This is nominative cardinal wording, not grammatical inflection. Expansion happens before bounded prompt/token chunking. Unsupported numeric formats report an error rather than being silently split and spoken incorrectly.

The local baseline uses two CPU threads, eight Supertonic generation steps and text chunks of at most 180 characters. Adapter configuration supports `numThreads` (clamped 1..4) and Supertonic `numSteps` (clamped 2..16); these remain catalog settings. The manager's speed slider applies a 0.5×–2× multiplier to the caller's rate once, with the final rate clamped to that range. The service takes each adapter's actual sample rate; Kokoro uses 24 kHz and the tested Supertonic runtime reported 44.1 kHz. Do not hardcode a shared sample rate in a client.

Stopping Supertonic suppresses obsolete PCM promptly but native computation may finish its current bounded chunk before unloading/switching. Edge validates and fully decodes a bounded MP3 turn before committing PCM. If offline, disconnected or stalled, it keeps retrying the same uncommitted text chunk with bounded backoff until recovery or cancellation; it does not skip the chunk, fall back or report success because an attempt failed. Fatal invalid-voice/authorization/configuration errors still surface. Framework Stop/replacement and changes to the saved voice/online capability cancel pending attempts. See [ARCHITECTURE.md](docs/ARCHITECTURE.md) for the contract, engine extension recipe and precise cancellation/privacy limits.

Pending retry depends on the requesting app remaining alive and keeping the request open. Following `QUEUE_ADD` text waits behind the pending chunk in order. Client Stop/shutdown, process death or OEM cleanup can end the request. Unchanged VN Reader automatic narration imposes a ten-minute timeout on each prepared chunk and then stops/reports a problem; LocalVoice cannot override that caller limit. Its engine initialization timeout is separate. No universal audiobook recovery across a lost client/process or that ten-minute narration limit is claimed.

Clients that do not pick a voice by name get the voice selected in the manager whenever its language matches the requested locale; the region only ranks other voices. Clients that pick a voice by name get exactly that voice.

Modern clients discover enabled online voices through `getVoices()` (marked network-required); `isLanguageAvailable()` counts an online voice's language only when that voice is selected in Local Voice. The legacy `ACTION_CHECK_TTS_DATA` intent checks **installed local resources only**. With Edge enabled and zero local models, it returns `CHECK_VOICE_DATA_FAIL`, so a legacy client that gates solely on that result may request a local-model installation even though modern online voice discovery works.

## Verification

Run all host (JVM) checks; needs the AAR from `fetch-runtime.ps1` and `ANDROID_HOME` or `local.properties`:

```powershell
.\gradlew.bat test        # ./gradlew test on Linux/macOS
```

Checks are `main()` programs with a thin JUnit `check()` entry: `app/src/test/java` (Android unit tests, real `org.json` via `com.vaadin.external.google:android-json`) and `hosttest/` (actual `ModelStore` compiled against stub `android.*`/`Catalog`; its production file list is in `hosttest/build.gradle`). Each check class runs in its own JVM. `ShtormBpeCheck` is skipped unless the untracked `research/shtorm/onnx` exists. Reports: `app/build/reports/tests/`, `hosttest/build/reports/tests/`.

The backend contract compiles actual production backend sources and uses injected fakes without loading native models. The Edge retry contract fault-injects transient/offline recovery, cancellation, real MP3/PCM staging limits and fatal errors against the production pure-Java helpers. The storage test uses assertions against actual disk helpers and catalog metadata. These suites do not prove Android API-26 runtime compatibility, JNI audio quality, real online recovery or framework callback behavior. The [test-only WebSocket fixture](tests/edge-fault-fixture/README.md) is used separately for emulator transport/codec fault injection.

Storage profiling methodology, raw evidence locations and current runtime-measurement limits are recorded in [VERIFICATION-PROFILE.md](docs/history/VERIFICATION-PROFILE.md).
Recorded automated results: **14 backend contract checks**, **26 Edge retry/audio fault checks**, protocol/`Retry-After` checks, storage self-check and **two host fixture tests passed**. Independent review reports and detailed evidence remain in the local workspace; private review reports are excluded from source packaging and repository publishing.

The 1.1 Android storage comparison used the same installed model files. One hundred main-thread installed-state calls—all asserting true—took 11,149 → 19 ms for Kokoro and 2,638 → 20 ms for Supertonic. Cached retry hashed 345,463,864 → 172,856,837 bytes for Kokoro and 290,621,550 → 145,310,775 for Supertonic, preserving complete first verification and conservative rechecks. These are emulator storage observations; boot/JIT/filesystem-cache differences confound wall-time ratios. Process-wide counters can include concurrent background refresh. See [VERIFICATION-PROFILE.md](docs/history/VERIFICATION-PROFILE.md) for exact counts, phase observations and remaining acceptance work.

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

## Source package and native source

Run from the LocalVoice root after final source/evidence review:

```powershell
.\package-source.ps1 -ListOnly
.\package-source.ps1 -OutputPath '..\dist\LocalVoice-source.zip'
```

The script uses [SOURCE-PACKAGE.json](SOURCE-PACKAGE.json) as an allowlist, refuses existing output files, adds a SHA-256 member manifest and writes the ZIP's SHA-256 beside it. It includes this project and sibling `research/native-source` plus exact license files. It excludes `signing`, passwords, private local scripts, model weights, SDKs/AVDs, dependency caches, `.gradle`, build outputs and APKs. Read the emitted member list before distribution; a `.gitignore` alone is not the packaging boundary.

The native source material includes pinned complete source snapshots, hashes and upstream build/workflow files for sherpa-onnx, piper-phonemize and eSpeak NG. See the sibling [native source manifest](../research/native-source/source-manifest.json) and [build/source notes](../research/native-source/BUILD-AND-SOURCE-NOTES.txt). Native libraries were not independently rebuilt here. The upstream workflow's `ANDROID_NDK_LATEST_HOME` does not pin the exact published compiler/NDK environment; building the application against the supplied AAR is distinct from proving a byte-for-byte native rebuild. Preserve all required application/native source and license materials with distribution.

The Sonic time-stretch adaptation retains its Apache-2.0 source/notices in [THIRD_PARTY_SONIC_NOTICES.txt](legal/THIRD_PARTY_SONIC_NOTICES.txt) and `third_party/androidx-media3-sonic`. GPL/LGPL application-component source obligations and independent model/voice attribution remain applicable; see the included notices and [source packaging boundary](docs/SOURCE-PACKAGING.md).
