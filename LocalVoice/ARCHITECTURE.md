# LocalVoice architecture and backend contract

LocalVoice is a separate Android system TTS engine (`org.localvoice.tts`). A client uses Android `TextToSpeech`, selects an advertised voice and receives mono PCM through the normal framework service. It does not depend on VN Reader being installed. VN Reader source was consulted read-only for the Edge protocol; the reused code and its notices are included in this GPL application.

## Responsibilities

| Component | Owns |
| --- | --- |
| `Catalog` | Stable model/voice IDs, language and network capabilities, backend ID, adapter configuration, pinned download revision, file sizes/SHA-256 and license metadata. |
| `MainActivity` | User selection, installation progress, explicit online consent, preview and links to Android TTS settings. |
| `ModelStore` / `ModelFiles` | Private model storage, verified staging/activation, download cancellation and retry, installed state, preferences and online opt-in. They do not create inference sessions. |
| `LocalVoiceService` | Android language/voice discovery, request validation, default 180-character chunks or metadata-selected adapter preprocessing, float-to-PCM16 conversion, callback start/audio/terminal handling, request cancellation. It selects adapters from catalog metadata; it contains no Kokoro/Supertonic model-ID branch. |
| `CheckVoiceDataActivity` | Legacy local-resource-data result, inspected on a worker so a cold main-thread installed snapshot cannot produce a false missing-data result. |
| `SerializedBackend` | One loaded adapter, load/inference/unload exclusion, cached load identity, request generation tokens and suppression of obsolete PCM. |
| `AndroidBackendHost` / `BackendStatus` | Injected Android connectivity/wake signals, process-local status and cancellation of the active request. Status contains phases/attempt counts, not synthesis text. |
| `SpeechBackend` implementations | Engine-specific initialization, language/speaker/speed translation, mono float PCM generation and resource release. |
| `BackendRegistry` | A small static mapping from reviewed backend IDs to adapter factories. This is not a dynamic plugin loader. |

Model installation state is independent of loaded model state. Discovering voices must not load a model or make a network request. Offline synthesis opens only verified installed files. Download/update work and preferences stay outside the service adapter contract.

Modern `getVoices()` / `isLanguageAvailable()` discovery includes enabled Edge metadata and marks those voices network-required. `ACTION_CHECK_TTS_DATA` deliberately reports local resources only, using ISO-3 language/country entries. With only Edge enabled and zero local models it returns `CHECK_VOICE_DATA_FAIL`. A legacy client relying only on that intent may prompt for local data even though online discovery works; this contract does not promise universal legacy-client compatibility. The post-suite worker fix passed a cold public intent with English US/UK and Russian available and no missing installed language.

Downloads run in the application process. Keep the app in the foreground until installation completes; this release does not promise unattended/background download completion. If Android kills the process, the user retries explicitly. Completed verified staging files are reused on retry, while a partial file restarts from its beginning. `tests/run-storage-selftest.ps1` exercises the disk installation rules; its result is recorded separately under `build/verification/storage-selfcheck.txt`.

Main-thread installed-state polling uses worker-owned snapshots. Generic per-file cache records require revision/hash/size plus device/inode/mtime/ctime identity; API-26, coarse, fresh or future identities conservatively rehash. Weak records never become trusted merely by aging. This generic cache policy is distinct from the following download-job proof.

The 1.1.1 downloader writes with exclusive creation/no-follow, forces and closes the sole writer, then performs one stored-byte SHA. Eligible large fresh files retain at most 16 read descriptors with FD/path identity. On supported Android versions a separate job-local proof can use app-owned, single-link, reversible0400 permissions set through the descriptor before SHA; final FD/path identity and mode must still match. Small, unsupported, reused and imported files receive ordinary final verification. Coarse timestamps alone never authorize cache trust. Root or hostile same-UID code that chmods, writes and restores mode within one coarse tick is outside the exclusive private-WORK namespace boundary; this is not fs-verity or cryptographic immutability.

Only a successfully atomically created, no-follow-checked staging leaf skips the initial reuse scan. Old empty/partial/corrupted/salvaged stages still scan and hash reusable files. Download-only path plans are lexical; strict original-root→leaf no-follow checks remain at actual write/hash/final boundaries, including after HTTP waits. Generic path resolution and imports retain their existing checks. Every file passes a final barrier and held descriptors close before receipt and atomic activation. Blocking receipt/cleanup I/O occurs outside the brief cancel/atomic-rename monitor; accepted cancellation cannot publish Ready, while a completed atomic commit cannot later become Cancelled.

Whole-job **Downloading and checking** covers connection, transfer and per-file checking with one cumulative byte total. Ordinary updates obey200 ms cadence; SHA does not add downloaded bytes. Initial and final/terminal phases remain immediate. Conditional UI setters preserve changed progress, retry, availability and metadata while leaving unchanged card text alone. See [VERIFICATION-PROFILE.md](VERIFICATION-PROFILE.md) for measured scope, exact counters and limits; process-wide counters may include background snapshot work.

Explicit repair can reuse an invalid installed directory when no staging directory already exists. The worker removes the old receipt, discards its verification-cache records and atomically moves that directory into unadvertised staging. Every staged file is verified against the pinned size/hash; only damaged or missing files are transferred. An existing staging directory is preserved, and a valid installed package returns without moving or downloading. Cancellation after salvage keeps the stage available for explicit retry while the model remains unavailable. Repair still uses the final all-file barrier and atomic publication; the old receipt or weak cache never authorizes reuse by itself.

`importModel(id, Uri)` accepts an explicit SAF selection. The storage worker acquires/journals its permission, copies a bounded compressed spool, verifies the exact archive SHA/size and complete ZIP structure, then permits only catalog-pinned runtime files plus the pinned manifest. Paths, duplicates, extra entries, CRC, decompressed sizes and SHA-256 are validated. The manifest is checked without being installed as configuration. The spool must be removed before publication. Runtime files are verified again and use the same final barrier/receipt/activation path as downloads. Cancellation closes the current provider stream asynchronously. App-owned URI grants are released after the job; startup reconciles its own stale journal entries while preserving preexisting grants. Process death requires explicit file selection to retry. The archive is a data package, not a plugin.

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
| `edge` | `voice` and exact service name/locale from selected metadata | Online Microsoft consumer Read Aloud speech, explicit opt-in; validated bundled/cache catalog with explicit refresh. |
| `sherpa-pocket` | `lmFlow`, `lmMain`, `encoder`, `decoder`, `textConditioner`, `vocabJson`, `tokenScoresJson`, `fixedVoice`, generation limits | One fixed Alba English (`en-GB`) voice from the historical INT8 export. |
| `sherpa-pocket-shtorm` | Pocket graphs plus `bpeVocab`, `bpeScores`, `bpeNormalizer`, `referenceAudio`; `chunking=backend`; import pins | Russian fast FP32 conversion, original Russian SentencePiece normalization/BPE, one VCTK p228 voice and explicit verified model-pack import. |

Model path values are relative to the verified model directory. Kokoro's eSpeak frontend data and lexicons accompany the model. Supertonic passes the requested language code through generation configuration; the same installed model supports both English and Russian.

Kokoro Russian remains unsupported: a Russian eSpeak dictionary in a multi-language package is not proof that the English Kokoro adapter supports Russian. Shtorm uses its own reviewed tokenizer bridge. SentencePiece normalization precedes bounded app-owned Russian cardinal expansion; the adapter then rechunks the complete request before BPE. It permits at most 256 text tokens per prepared chunk. Prompt chunking reserves room for punctuation and capitalization so prepared human text remains within 180 characters. Decimal/date/time/leading-zero/identifier numeric formats are rejected before service punctuation splitting can change their meaning. Static catalog `chunking=backend` selects this adapter-owned preprocessing; the service contains no model-ID branch.

Pocket generation has bounded frame/step limits and uses Sonic PCM time stretching for requested playback speed. The English catalog entry is a historical INT8 checkpoint with one fixed Alba voice; Shtorm is a separate FP32 import-only conversion with one VCTK p228 reference voice. The coordinator's generation cancellation and serialized unload rules apply to both. Native computation may finish its bounded segment after audio cancellation; unloading waits for inference to return. A deterministic host signal test checks pitch preservation, while actual Android rate/WAV tests establish changed duration and valid output rather than perceptual quality.

The signed D129 candidate's exhaustive offline public-client suite produced nonempty output from all four local families (Kokoro/Pocket English/Shtorm Russian 24 kHz; Supertonic English/Russian 44.1 kHz), exercised active PCM Stop in both Pocket adapters without late PCM/done, and passed replacement, restart and unsupported-text error checks. Real Android download/import cancellation and retry and a restored-mtime tiny-file repair also passed. Candidate 8eb changed only the data-check activity worker and passed offline Pocket/Shtorm smoke plus production Edge English/Russian. Final 1.1.0/code 2 (`1da7f5dbc38c95b0cc570275572570c26c8e305c7f3f3f7cc5907ffc89ef700c`) changes only navigation text from 14 to 12 sp; its own offline Pocket/Shtorm smoke and cold local-data check passed. This lineage preserves the scope of the earlier full suite rather than claiming it was rerun on every artifact.

Final UI checks covered small-width/large-font navigation, landscape/IME, dark mode, full license display and stacked 48 dp Details actions. Exact Alba preview left the saved default unchanged; voice-row selection persisted as intended. UI multiplier 1.5× combined once with caller 1.25× to give 1.875×. Unchanged VN Reader 3.4.4 used its System TTS provider and saved Alba voice, with service first PCM and done observed; VN-specific audio was not separately recorded or subjectively evaluated. Original VN preferences, Android TTS/display/network settings were restored and the owned test emulator was stopped. See the README, [verification profile](VERIFICATION-PROFILE.md) and [source packaging boundary](SOURCE-PACKAGING.md). No physical-phone or subjective-quality performance claim follows from these emulator results.

## Online boundary and privacy

Edge is disabled initially. The user explicitly enables its online capability and chooses an online voice. Android voice metadata marks it as requiring a network connection. A failed local voice never silently falls back to Edge. Recoverable Edge failures wait/retry the same pending text; fatal configuration/authorization failures report an error.

The repository initializes bundled/private cached Edge metadata asynchronously without HTTP, including headless service startup. Its bundled snapshot has 322 voices and 142 locales. Explicit refresh is bounded to 20 seconds and 1 MiB, validates the entire snapshot, and atomically replaces the private cache/catalog. Voice IDs remain `edge:ShortName`; the original four IDs stay valid. Disabling online capability revokes its preference and discovery immediately, outside the storage work queue; the service rechecks consent and cancels an active matching online request, leaving local synthesis unaffected.

Actual cold headless discovery exposed 322 network voices among 372 total with all local models installed. An offline request stayed pending until explicit UI Disable, then surfaced an error without PCM/successful done. Restoring Wi-Fi did not revive the revoked request; subsequent discovery exposed 50 local/zero network voices. Production French `fr-FR-DeniseNeural` also generated PCM. The 236,398 ms error timestamp in the callback log includes Android freezing the caller and is not a cancellation-latency measurement; the live pending interval before revocation was about 160 seconds.

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

The historical 1.0 production release SHA-256 is `e7e6945e32454e2ce2321671cfa57e51c9c87c194ae3bc36aae8e2d8991e9538`; its installed bytes matched. Production English/Russian Edge, local English/Russian/Kokoro with settled network `None`, rate changes, process restart and Stop during PCM/backpressure were verified through Android's system client. The [README](README.md#verification) separately records final 1.1 artifact lineage and measured scope. Controlled server faults, production service availability and physical-device performance are separate claims.

The JVM tests run with Java 17 and do not detect every Android library API-level incompatibility. The Android build/lint and an API-26 emulator/device check are separate validation steps for the declared minimum SDK. Passing the JVM suite alone does not establish minimum-API compatibility.

## Source and native rebuild boundary

The application source builds against the pinned sherpa-onnx 1.13.8 AAR in `app/libs`; its recorded upstream SHA-256 is `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`. The delivery includes native source snapshots and hash manifests for sherpa-onnx, its pinned piper-phonemize and eSpeak NG dependencies, plus upstream Android workflow/build scripts. See the sibling [native source manifest](../research/native-source/source-manifest.json) and [build/source notes](../research/native-source/BUILD-AND-SOURCE-NOTES.txt); retain these materials and required license notices with redistributed source/APK packages.

Those native snapshots were preserved, not rebuilt in this session. The upstream Android workflow uses its runner's `ANDROID_NDK_LATEST_HOME`; it does not pin the exact published binary's NDK/compiler environment. Its native build also uses separately fetched support dependencies/ONNX Runtime binaries. The application build using the supplied pinned AAR is distinct from reproducing that AAR from native source. No byte-for-byte native binary rebuild or fully offline native build has been verified. Use the included source notes and upstream workflow for that work rather than treating a successful application build as native reproducibility evidence.

## Performance baseline

The intended minimum device targets are Pixel 7 Pro and Huawei Pura 70. The portable implementation uses ARM64 CPU inference and quantized local models; it requires no Google Play Services, NNAPI, GPU/NPU provider or Google-specific accelerator. The sherpa runtime also contains an x86_64 emulator ABI for integration verification.

Current defaults are two CPU inference threads, one native sentence per generation call, eight Supertonic generation steps and 180-character service chunks. Backend load configuration accepts `numThreads` clamped to 1..4 and Supertonic `numSteps` clamped to 2..16; no tuning UI is exposed. Two threads is a conservative CPU baseline that avoids consuming every core; eight steps preserves the runtime's intended quality baseline. Chunking trades some prosody and throughput for bounded pending work and earlier opportunity to honor stop. Thread/step alternatives must be measured with identical text, model revision, speaker and audio duration before changing these defaults. The adapter supplies the actual sample rate: the tested Supertonic runtime reports 44.1 kHz, while Kokoro uses 24 kHz.

Record cold model load, warm first-PCM latency, synthesis wall time, generated audio duration/real-time factor and process memory separately. Compare threads/steps with repeated warm requests, and separately verify stop/switch latency. Emulator numbers measure that emulator/host setup only. They are not Pixel/Huawei speed, thermal, battery or sustained performance measurements. Physical-device and extended thermal checks remain required before claiming the minimum devices meet a numerical performance target.
