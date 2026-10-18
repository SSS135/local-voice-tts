# LocalVoice architecture and backend contract

LocalVoice is a separate Android system TTS engine (`org.localvoice.tts`). A client uses Android `TextToSpeech`, selects an advertised voice and receives mono PCM through the normal framework service. It does not depend on VN Reader being installed. VN Reader source was consulted read-only for the Edge protocol; the reused code and its notices are included in this GPL application.

## Responsibilities

| Component | Owns |
| --- | --- |
| `Catalog` | Stable model/voice IDs, language and network capabilities, backend ID, adapter configuration, pinned download revision, file sizes/SHA-256 and license metadata. |
| `MainActivity` | User selection, installation progress, explicit online consent, preview and links to Android TTS settings. |
| `ModelStore` / `ModelFiles` | Private model storage, verified staging/activation, download cancellation and retry, installed state, preferences and online opt-in. They do not create inference sessions. |
| `LocalVoiceService` | Android language/voice discovery, request validation, 180-character text chunks, float-to-PCM16 conversion, callback start/audio/terminal handling, request cancellation. It selects adapters from catalog metadata; it contains no Kokoro/Supertonic model-ID branch. |
| `SerializedBackend` | One loaded adapter, load/inference/unload exclusion, cached load identity, request generation tokens and suppression of obsolete PCM. |
| `AndroidBackendHost` / `BackendStatus` | Injected Android connectivity/wake signals, process-local status and cancellation of the active request. Status contains phases/attempt counts, not synthesis text. |
| `SpeechBackend` implementations | Engine-specific initialization, language/speaker/speed translation, mono float PCM generation and resource release. |
| `BackendRegistry` | A small static mapping from reviewed backend IDs to adapter factories. This is not a dynamic plugin loader. |

Model installation state is independent of loaded model state. Discovering voices must not load a model or make a network request. Offline synthesis opens only verified installed files. Download/update work and preferences stay outside the service adapter contract.

Downloads run in the application process. Keep the app in the foreground until installation completes; this release does not promise unattended/background download completion. If Android kills the process, the user retries explicitly. Completed verified staging files are reused on retry, while a partial file restarts from its beginning. `tests/run-storage-selftest.ps1` exercises the disk installation rules; its result is recorded separately under `build/verification/storage-selfcheck.txt`.

## Adapter API

`SpeechBackend` is a synchronous Java interface:

```java
void load(File directory, Map<String, String> config) throws Exception;
int sampleRate();
void synthesize(String text, String languageTag, int speakerId, float speed,
                Cancellation cancellation, PcmSink sink) throws Exception;
void cancel(); // concurrent, best effort; never frees inference resources
void close();
```

Adapters can also implement optional `setHost(SpeechBackend.Host)`. The service factory supplies that host before load; it exposes network availability, a cancelable connectivity/backoff wait and phase/attempt status reporting. Local adapters can retain the default no-op implementation. This keeps Android connectivity/UI code outside the reusable retry/PCM helpers and avoids a service branch for a particular model.

`PcmSink.write(float[])` returns whether output should continue. Each array contains mono float samples at `sampleRate()`. Adapters should supply finite samples in the normal -1..1 range; the Android boundary clips to PCM16. `sampleRate()` is valid after successful `load`. PCM callbacks occur on the synthesis caller thread and end before `synthesize` returns. Adapters must not retain the sink for asynchronous writes. The sink consumes an array before returning; an adapter can then reuse its storage.

The coordinator calls `Ready.onReady(sampleRate)` before inference so Android output is initialized before the first PCM block. Initialization, local synthesis and fatal online failures propagate to the service, which reports a synthesis/network error for a live request. Recoverable Edge transport/validation failures stay inside its retry loop without completing the Android request. A failed load is closed immediately and is never cached as a successfully loaded adapter. A later request can retry. `close` must be idempotent and release all native sessions, sockets and temporary decoder resources owned by the adapter.

`SerializedBackend` caches the model ID, backend kind, directory and a copied configuration map. A change in any of them releases the previous adapter before loading its replacement. In particular, two Edge voices share one model ID but use different `voice` configuration: changing Aria to Guy must reload that configuration. A stable identical configuration reuses the existing session. Language, speaker and speed passed to `synthesize` remain request parameters.

## Cancellation and lifetime

`begin()` creates a generation token. A later `begin()` or `cancel()` invalidates earlier tokens. `execute()` checks that token before load, after load and at PCM delivery. Once a sink returns false, the coordinator keeps that sink closed for the remainder of that inference even if the backend keeps calling it. A new generation is independent of an earlier stop.

`cancel()` does not acquire the inference or output sink lock. It invalidates the generation immediately and invokes the adapter's concurrent best-effort `cancel()`. An output callback already in flight may finish; the service checks cancellation while converting/buffering PCM, and the Android framework rejects stopped output. The coordinator does not hold a cancellation lock while calling a possibly blocked Android sink.

Load, synthesis, model switching and close share one inference monitor. Replacement and destruction wait until active native inference has returned; they never free a native model in use. An adapter's concurrent `cancel()` may interrupt network I/O, but must not unload model/decoder resources concurrently with synthesis.

Service destruction invalidates the active generation immediately and schedules serialized adapter cleanup off the main thread. The manager's **Cancel current speech request** action also reaches the bound service when another app owns the currently active backend request; after synthesis unwinds, the callback is terminated as an error. Once backend generation has returned and only another app's framework playback remains, the manager cannot universally stop that playback through the public API. It does not erase another client's remaining `QUEUE_ADD` requests. Each client owns its playback/queue and must use its own Stop/queue-flush control. The manager separately stops its own preview client. Voice validation/preloading through `onLoadVoice` does not cancel an already valid queued request.

**Supertonic limitation:** sherpa-onnx 1.13.8 can ignore the native callback's stop return value. Stopping audio therefore does not guarantee immediate termination of native computation. Obsolete PCM is discarded, and the service limits each request segment to at most 180 characters. This bounds the pending text segment, not a guaranteed stop duration. Model switch/unload waits for that inference to finish. The contract test deliberately uses a stubborn fake that continues callbacks after stop to verify this behavior.

## Shipped adapter configuration

| Backend ID | Configuration | Capability |
| --- | --- | --- |
| `sherpa-kokoro` | `model`, `voices`, `tokens`, `dataDir`, `lang`, `lexicon` | Quantized Kokoro v1 English, US/UK voice table; local CPU inference. |
| `sherpa-supertonic` | `durationPredictor`, `textEncoder`, `vectorEstimator`, `vocoder`, `ttsJson`, `unicodeIndexer`, `voiceStyle` | Quantized Supertonic 3, English/Russian, ten style IDs; local CPU inference. |
| `edge` | `voice` from the selected voice metadata | Online Microsoft consumer Read Aloud speech, explicit opt-in, four embedded baseline English/Russian voices. |

Model path values are relative to the verified model directory. Kokoro's eSpeak frontend data and lexicons accompany the model. Supertonic passes the requested language code through generation configuration; the same installed model supports both English and Russian.

Kokoro Russian/Shtorm is shown as unavailable until an exact compatible Russian frontend/token mapping and model license are verified. A Russian eSpeak dictionary in a multi-language package is not proof that the English Kokoro adapter supports Russian.

## Online boundary and privacy

Edge is disabled initially. The user explicitly enables its online capability and chooses an online voice. Android voice metadata marks it as requiring a network connection. A failed local voice never silently falls back to Edge. Recoverable Edge failures wait/retry the same pending text; fatal configuration/authorization failures report an error.

Only a synthesis request for a selected online voice sends its text to Microsoft's service. Catalog display, local voice discovery and selecting a local voice do not contact Microsoft. The UI discloses the recipient, network requirement and unofficial endpoint. Microsoft's consumer service availability and terms apply independently of the application license.

The adapter receives a complete bounded MP3 turn (at most 2 MiB), validates complete MPEG frames, and decodes the whole turn through Android `MediaCodec` before committing any PCM to the common sink. PCM staging is bounded to 4,320,000 finite float samples (180 seconds at the online adapter's 24 kHz rate, approximately 17.3 MB of sample data plus compressed audio/decoder/container overhead). Staging is in memory, with no persistent audio spool. This implementation does not provide live network-to-decoder audio streaming. The service's 180-character text chunks bound each turn. First-audio latency must be measured at first PCM delivery, not at WebSocket connection or output `start`.

### Edge retry and chunk commit

An unavailable network, DNS/socket failure, disconnect, stalled attempt, recoverable service response or malformed/truncated/decode-failed audio keeps the same uncommitted text chunk pending. An attempt may have its own network/decode timeout; expiration triggers a new attempt rather than abandoning the chunk. Retry has no fixed attempt count or total outage deadline. Exponential backoff starts at 250 ms, uses jitter and caps normal waits at 15 seconds; offline connectivity waits avoid issuing requests without an available network and can wake when connectivity changes. A server `Retry-After` delay can span multiple bounded waits. Stop is checked during waits and before/after an attempt.

Only a fully validated, completely decoded turn can commit PCM. Failed attempts discard their temporary MP3/PCM; advancing the text offset happens only after successful commit. Thus retrying a truncated/decode-failed turn cannot play its partial prefix and then repeat it on recovery. Once PCM delivery begins, a rejected/stopped sink ends that commit and is never treated as a transport failure to replay. A fatal invalid voice/configuration, authorization rejection or incompatible protocol response exits with an error, without skip, success or fallback.

Framework Stop/queue replacement, manager cancellation and changes to the saved voice/online capability invalidate the active request generation and cancel in-flight I/O/backoff. A late successful result is checked against cancellation before commit, and staged PCM checks it between blocks. Later `QUEUE_ADD` requests stay behind the live pending head request, preserving order until recovery or cancellation. The retry helpers do not own a second persistent queue; cancelling its head request does not clear another client's full framework queue. Disabling online voices removes them from discovery and queued requests that still name those disabled voices fail as unavailable instead of resuming network speech. Changing a saved default does not rewrite another client's explicitly selected queued voices.

This guarantee applies only while the same live client request remains pending. Android process death, OEM process management, client shutdown and explicit/automatic client Stop end that request; the engine cannot override them or reconstruct a lost client queue. Source review found no wall-clock idle timeout in the AOSP synchronous synthesis/playback/file callback path used here. Unchanged VN Reader automatic narration has its own **600,000 ms (ten-minute) timeout per prepared text chunk**; it stops/reports a problem after that limit. VN Reader's normal TTS completion/settings preview has no corresponding outer narration timeout, while its 15-second engine initialization timeout concerns startup. LocalVoice does not change any VN Reader file or promise automatic narration recovery beyond that caller's limit.

## Adding an engine

1. Implement `SpeechBackend` in the backend package. Keep model-specific APIs, tokenization and decoder/network code inside the adapter. Validate required configuration at load. Load no model from a voice-discovery/UI callback. Respect synchronous PCM, best-effort cancellation and idempotent cleanup.
2. Add one factory to `BackendRegistry` under a stable backend ID. No service model-ID condition is needed. The factory constructor should be cheap; allocate inference resources in `load`.
3. Add catalog model/voice metadata with that backend ID, supported language tags and actual speaker IDs. Pin each downloaded artifact to an immutable revision, HTTPS URL, exact size and SHA-256. Add the correct upstream model/frontend license and in-app notice. A new file layout belongs in catalog configuration and its adapter.
4. For a network engine set the network capability, implement explicit consent in the existing online store/UI flow, advertise network-required Android voices and provide precise recipient/privacy text. Never introduce automatic local-to-network fallback.
5. Extend contract/fake tests for new lifecycle assumptions. Add a small adapter-specific fixture test for configuration mapping, malformed input and cancellation. Keep tests independent of downloaded production model weights whenever possible.
6. Run the JVM checks and Android build, then the client probe on an emulator/device: discover/select voices, short and long synthesis, first PCM/nonempty output, stop and immediate new request, failure reporting, same-model voice switch, cross-model switch, activity/process/service recreation, offline operation after installation and network opt-in/revocation. Record model revision, runtime, ABI and audio evidence.

Use the existing static registry and catalog for new adapters. Additional plugin packaging, remote registries or a background inference queue are unnecessary for the current scope.

## Tests and evidence

From a PowerShell prompt at the project root:

```powershell
.\tests\run-backend-contract.ps1
.\tests\run-edge-retry.ps1
```

The runner compiles production backend sources with `javac` and runs `BackendContractTest` on a regular JVM. It uses Android's SDK jar and cached sherpa/OkHttp/Okio/Kotlin classes only to compile concrete adapter references; the fake tests do not instantiate native sessions, call Android APIs, access the network or read model files. Supply `-JavaHome`, `-AndroidSdk`, `-KotlinStdlib` or `-GradleCache` when the local paths differ. Populate dependency caches with the normal Android build first. Each invocation uses a new output directory under `build/backend-contract` and writes `result.txt`.

The 14 coordinator tests cover PCM parameters/order/thread, reuse/switch, changed per-voice load configuration, load/inference/output-start failures and recovery, stop before/during load, native callbacks after cancel, sink stop latching, cancellation during output backpressure, new generation isolation, serialized switching and close waiting for inference. `EdgeProtocolCheck` additionally checks GEC vectors, XML escaping, rate bounds, malformed frame rejection and embedded voice metadata. These checks do not prove audio quality or Android framework terminal-callback behavior; the emulator client probe supplies the integration evidence.

`EdgeRetryContractTest` has 26 passing checks against production `EdgeRetry` and `EdgeAudio` helpers with a fake transport/virtual wait clock, plus one bounded real cancellation-wake check. It injects 10,000 offline waits, 512 recoverable failures before recovery, truncated MP3, decoder failure after temporary PCM, cancellation, replacement and fatal errors. It checks ordered one-time commit, MP3/PCM bounds, completed-stage sealing and cancelable `Retry-After` waits. It does not simulate Android `MediaCodec` internals or prove real Microsoft availability. The [test-only fixture](tests/edge-fault-fixture/README.md) supports real Android transport/codec fault injection separately; generated logs/audio belong under `build`, outside source packaging.

The controlled Android debug-fixture/public-client run independently verified three ordered chunks matching 152,064 baseline PCM bytes after partial close, truncation and stall, plus thirteen HTTP 503 failures over four minutes before recovery of the same request. Stop during backoff/in-flight transport and old-voice replacement emitted no stale PCM. Cross-client manager cancellation produced `ERROR_SYNTHESIS` without successful completion; the probe recorded two error notifications. A real HTTP 403 response was not injected, so authorization classification evidence remains source review plus JVM fatal-error tests.

The frozen production release SHA-256 is `e7e6945e32454e2ce2321671cfa57e51c9c87c194ae3bc36aae8e2d8991e9538`; installed bytes match. Production English/Russian Edge, local English/Russian/Kokoro with settled network `None`, rate changes, process restart and Stop during PCM/backpressure were verified through Android's system client. The [README](README.md#verification) records the measured scope and timings. Controlled server faults, production service availability and physical-device performance are separate claims.

The JVM tests run with Java 17 and do not detect every Android library API-level incompatibility. The Android build/lint and an API-26 emulator/device check are separate validation steps for the declared minimum SDK. Passing the JVM suite alone does not establish minimum-API compatibility.

## Source and native rebuild boundary

The application source builds against the pinned sherpa-onnx 1.13.8 AAR in `app/libs`; its recorded upstream SHA-256 is `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`. The delivery includes native source snapshots and hash manifests for sherpa-onnx, its pinned piper-phonemize and eSpeak NG dependencies, plus upstream Android workflow/build scripts. See the sibling [native source manifest](../research/native-source/source-manifest.json) and [build/source notes](../research/native-source/BUILD-AND-SOURCE-NOTES.txt); retain these materials and required license notices with redistributed source/APK packages.

Those native snapshots were preserved, not rebuilt in this session. The upstream Android workflow uses its runner's `ANDROID_NDK_LATEST_HOME`; it does not pin the exact published binary's NDK/compiler environment. Its native build also uses separately fetched support dependencies/ONNX Runtime binaries. The application build using the supplied pinned AAR is distinct from reproducing that AAR from native source. No byte-for-byte native binary rebuild or fully offline native build has been verified. Use the included source notes and upstream workflow for that work rather than treating a successful application build as native reproducibility evidence.

## Performance baseline

The intended minimum device targets are Pixel 7 Pro and Huawei Pura 70. The portable implementation uses ARM64 CPU inference and quantized local models; it requires no Google Play Services, NNAPI, GPU/NPU provider or Google-specific accelerator. The sherpa runtime also contains an x86_64 emulator ABI for integration verification.

Current defaults are two CPU inference threads, one native sentence per generation call, eight Supertonic generation steps and 180-character service chunks. Backend load configuration accepts `numThreads` clamped to 1..4 and Supertonic `numSteps` clamped to 2..16; no tuning UI is exposed. Two threads is a conservative CPU baseline that avoids consuming every core; eight steps preserves the runtime's intended quality baseline. Chunking trades some prosody and throughput for bounded pending work and earlier opportunity to honor stop. Thread/step alternatives must be measured with identical text, model revision, speaker and audio duration before changing these defaults. The adapter supplies the actual sample rate: the tested Supertonic runtime reports 44.1 kHz, while Kokoro uses 24 kHz.

Record cold model load, warm first-PCM latency, synthesis wall time, generated audio duration/real-time factor and process memory separately. Compare threads/steps with repeated warm requests, and separately verify stop/switch latency. Emulator numbers measure that emulator/host setup only. They are not Pixel/Huawei speed, thermal, battery or sustained performance measurements. Physical-device and extended thermal checks remain required before claiming the minimum devices meet a numerical performance target.
