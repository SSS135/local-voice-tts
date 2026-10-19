# Full Edge voice catalog

The bundled metadata snapshot was fetched successfully from Microsoft's consumer
Read Aloud `voices/list` endpoint on **2026-10-18 at 18:46:14.277 UTC**. It contains
**322 voices across 142 distinct Locale values**. This records what the endpoint
returned on that date; availability can change. No model weights are bundled or
downloaded for Edge.

All 322 entries in this response have `(Natural)` in their actual FriendlyName
and `Neural` in their ShortName. The app preserves those labels rather than
assigning its own quality tiers. Natural entries sort first if a later response
contains mixed labels; otherwise sorting uses locale and the actual friendly name.

## Privacy and discovery

Online speech remains disabled until the user explicitly enables it after the
privacy notice. Catalog metadata can be browsed from the bundled or cached list
offline. An explicit Enable/Refresh action may request current metadata; it sends
no utterance text. Binding the system TTS service, listing voices, constructing
ModelStore, and reading the catalog do not request metadata from the network.
Speech requests using an explicitly enabled online voice send their text to
Microsoft, as described in the application's privacy notice.

`EdgeCatalogRepository.initialize(Context)` loads local metadata on a dedicated
worker. `get(Context)` waits for that initialization; UI code calls it on its own
worker and refreshes the view after completion. The service/store startup
integration must initialize the repository before answering voice discovery, so
reopening the UI is not necessary after process death. Subsequent snapshot access
uses memory. The initialization worker and voice-discovery readiness integration
are verified separately on Android.

When Edge is enabled, the system engine advertises every cached Edge voice with
`networkRequired=true`. A stale catalog is not proof that a removed voice still
works. Fatal voice/authentication/configuration errors surface to the client;
synthesis does not switch providers after an error. Recoverable speech failures
retain the same pending chunk under the separate speech retry policy.

## Identity and languages

Android voice IDs remain `edge:<ShortName>`. The original Aria, Guy, Svetlana, and
Dmitry IDs are unchanged, so existing saved selections need no migration. Every
entry carries the actual service Name and full Locale in backend configuration.
This includes script and regional tags such as `iu-Latn-CA`, `iu-Cans-CA`,
`zh-CN-liaoning`, and `zh-CN-shaanxi`; the adapter does not reconstruct their voice
names by assuming a two-part locale. A voice change changes the configuration
cache key and reloads the backend before synthesis.

## Validation, caching, and cancellation

A metadata refresh is a single cancellable request with a 20-second deadline.
The response is bounded to 1 MiB and 5,000 entries. Required field types, string
lengths, Unicode/control characters, complete BCP47 locale representation,
Name/Locale/ShortName consistency, duplicate IDs, UTF-8, and complete JSON are
validated before publication. No malformed rows are silently skipped.

The complete validated response and fetch timestamp are stored in the app's
private no-backup directory. A temporary file is flushed and atomically replaces
the cache. Failed, malformed, or cancelled refreshes preserve the previous cache
and its timestamp. A corrupt startup cache falls back to the complete dated
bundled snapshot and exposes a warning; it does not trigger a network request.

The UI creates a cancellation token before queuing refresh work and passes it to
`refresh(Cancellation)`. Cancellation before execution opens no HTTP connection.
Active cancellation closes that refresh's own OkHttp call and prevents cache or
catalog publication. This metadata cancellation does not cancel an unrelated
speech request.

## Checks

`EdgeCatalogCheck` (`app/src/test`, run by `gradlew test`) exercises the actual parser, cache, dynamic catalog,
stable IDs, and exact SSML configuration for every bundled voice. Tests use
Android's org.json implementation (`com.vaadin.external.google:android-json`,
test-only); production adds no JSON library dependency.

`EdgeCatalogRepositoryCheck` additionally executes the actual repository
and OkHttp transport against an isolated loopback HTTP fixture: offline startup,
queued cancellation without HTTP, full publication, truncated refresh retention,
active socket cancellation, offline cache restart, and corrupt-cache recovery.
These host tests do not launch a browser or emulator. Android UI/system-client
verification is tracked separately in the release validation report.

At implementation handoff, the parser/cache/SSML suite passes 14 checks and the
actual repository/HTTP suite passes 8 checks, including backend configuration
validation for all 322 voices. The fixture records counts and timing only; it
does not print the full catalog, utterance text, or preferences. The cached
Android OkHttp artifact emits a caught Android logging-stub warning on the host
JVM before choosing its JVM fallback; the real HTTP checks complete successfully.
Actual Android `onGetVoices()` count, enable/disable behavior, locale availability,
and UI interaction checks remain pending while emulator/browser launches are
paused at the user's request.
