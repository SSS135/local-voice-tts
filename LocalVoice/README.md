# LocalVoice

For a Git checkout, run `.\fetch-runtime.ps1` before the build to retrieve and
verify the pinned AAR. See the [repository entry point](../README.md) for native
source retrieval and source-package preparation; those large archives are
kept downloadable rather than committed.

A separate Android system text-to-speech engine for local English/Russian speech and explicitly enabled Microsoft online voices. Package: `org.localvoice.tts`. Android minimum SDK 26; target/compile SDK 36. Local CPU inference supports ARM64 phones, including the intended Pixel 7 Pro and Huawei Pura 70 targets, without Google Play Services or an accelerator service. Physical-phone speed, battery and thermal behavior have not been measured.

The application is GPL-3.0; see [LICENSE](LICENSE). The Microsoft Edge protocol adaptation from VN Reader and its upstream LGPL component retain their separate notices in [THIRD_PARTY_EDGE_NOTICES.txt](THIRD_PARTY_EDGE_NOTICES.txt) and [EDGE_TTS_LICENSE.txt](EDGE_TTS_LICENSE.txt). Model licenses are independent: Kokoro weights Apache-2.0, its eSpeak frontend GPL-3.0, and Supertonic 3 weights OpenRAIL-M. The source archive includes no model weights. The in-app catalog discloses licenses before download/use. VN Reader is not required or modified by this application.

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

The copied standard wrapper bootstrap jar has SHA-256 `d3b261c2820e9e3d8d639ed084900f11f4a86050a8f83342ade7b6bc9b0d2bdd`, listed for Gradle's 8.6 bootstrap; it launches the pinned 9.5.1 distribution. The distribution SHA-256 is `bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f`. Both were checked against the [official Gradle checksum reference](https://gradle.org/release-checksums/). Wrapper scripts/jar retain Apache-2.0 terms in [GRADLE_WRAPPER_LICENSE.txt](GRADLE_WRAPPER_LICENSE.txt).

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
- Microsoft Edge voices require explicit online enablement. Speech text is sent to Microsoft only for a requested online voice. Local failures do not fall back to the network. The online endpoint is unofficial and can change.

Downloads are process-bound: after a killed app, use Retry. Completed verified files are reused; a partial file starts again. No background completion guarantee is made. Online voices can be disabled in their model card. Unavailable Kokoro-Russian/Shtorm is deferred until a compatible frontend and exact license are verified.

The local baseline uses two CPU threads, eight Supertonic generation steps and text chunks of at most 180 characters. Adapter configuration supports `numThreads` (clamped 1..4) and Supertonic `numSteps` (clamped 2..16); there is no tuning UI. Change catalog/config values and rebuild to compare settings. The service takes each adapter's actual sample rate; Kokoro uses 24 kHz and the tested Supertonic runtime reported 44.1 kHz. Do not hardcode a shared sample rate in a client.

Stopping Supertonic suppresses obsolete PCM promptly but native computation may finish its current bounded chunk before unloading/switching. Edge validates and fully decodes a bounded MP3 turn before committing PCM. If offline, disconnected or stalled, it keeps retrying the same uncommitted text chunk with bounded backoff until recovery or cancellation; it does not skip the chunk, fall back or report success because an attempt failed. Fatal invalid-voice/authorization/configuration errors still surface. Framework Stop/replacement, manager cancellation and changes to the saved voice/online capability cancel pending attempts. See [ARCHITECTURE.md](ARCHITECTURE.md) for the contract, engine extension recipe and precise cancellation/privacy limits.

Pending retry depends on the requesting app remaining alive and keeping the request open. Following `QUEUE_ADD` text waits behind the pending chunk in order. Client Stop/shutdown, process death or OEM cleanup can end the request. Unchanged VN Reader automatic narration imposes a ten-minute timeout on each prepared chunk and then stops/reports a problem; LocalVoice cannot override that caller limit. Its engine initialization timeout is separate. No universal audiobook recovery across a lost client/process or that ten-minute narration limit is claimed.

The manager's **Cancel current speech request** button can cancel another app's request while its backend generation/retry is active. Once generation has finished and only that app's framework playback remains, this button cannot universally stop the other app's audio. It also does not clear that app's remaining queue; use the requesting app's own Stop/queue-flush control for playback and its full queue. The manager can stop its own preview playback.

## Verification

After the normal build has populated dependencies, run the JVM checks:

```powershell
.\tests\run-backend-contract.ps1
.\tests\run-edge-retry.ps1
.\tests\run-storage-selftest.ps1
```

The backend runner accepts JDK/SDK/cache/Kotlin overrides; use `-GradleCache 'C:\cache\caches\modules-2\files-2.1'` if your cache differs. It compiles actual production backend sources and uses injected fakes without loading native models. The isolated Edge retry runner needs only a JDK; it fault-injects transient/offline recovery, cancellation, real MP3/PCM staging limits and fatal errors against the production pure-Java helpers. The storage test uses assertions against actual disk helpers and catalog metadata. These suites do not prove Android API-26 runtime compatibility, JNI audio quality, real online recovery or framework callback behavior. The [test-only WebSocket fixture](tests/edge-fault-fixture/README.md) is used separately for emulator transport/codec fault injection.

Recorded automated results: **14 backend contract checks**, **26 Edge retry/audio fault checks**, protocol/`Retry-After` checks, storage self-check and **two host fixture tests passed**. The independent `REVIEW.md` in the source delivery records its scope; this private report is excluded from repository publishing.

The final signed production APK has SHA-256 `e7e6945e32454e2ce2321671cfa57e51c9c87c194ae3bc36aae8e2d8991e9538`; its installed APK checksum matches, and the original signing certificate was preserved. Verified through the Android system client on the API-36 x86_64 emulator:

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
