# Edge host fault fixture

Test source only; Python3 standard library, with optional local ffmpeg for generating audio. Nothing in this directory is an Android production endpoint, exported activity, or release asset. The debug test APK injects `new EdgeBackend("http://127.0.0.1:8765/edge",2000L)` through its instrumentation factory; the production factory keeps its real endpoint. The Android build/emulator owner runs integration tests.

From the repository root, prepare a public, reproducible 440Hz tone (one second, mono24kHz, MP3 at48kbps). Add ffmpeg to PATH or provide its executable path. No service request or personal recording is needed:

```powershell
& tests/edge-fault-fixture/prepare-audio.ps1
python -B -m unittest discover -s tests/edge-fault-fixture -p test_server.py -v
python -B tests/edge-fault-fixture/server.py --mp3 build/edge-fixture/valid.mp3 --log build/edge-fixture/trace.jsonl --scenario tests/edge-fault-fixture/faults.json
```

Use a fresh trace filename for each integration scenario. Generated audio, traces and provenance stay under `build/edge-fixture`; they are excluded from source delivery. `audio-provenance.json` records source kind, MP3 hash, reference PCM hash and measured sizes. Different ffmpeg versions may encode different bytes; compare a run against its own baseline, not a hardcoded codec hash. `reference.pcm` is signed16le24kHz mono ffmpeg output, useful for transport integrity. Android's codec output can differ numerically from ffmpeg; compare Android fault/recovery output to Android's clean response baseline for exact no-duplicate/no-skip assertions.

The server binds only127.0.0.1. GET `/health` reports readiness, MP3 hash and request count. Android owner runs `adb reverse tcp:8765 tcp:8765` then uses the debug test APK factory injection. Use `2000ms` per-attempt timeout so the supplied6second stall fails promptly. The source fixture must not enter release APK assets or production manifests.

Default `faults.json` performs these global connection actions, then repeats success:

1. Send800 MP3 bytes, then abort without `turn.end`.
2. Send all but the last MP3 byte followed by `turn.end`. The streaming adapter plays the complete frames and drops the incomplete final frame.
3. Accept SSML and stall6seconds. Observe client cancellation after the bounded attempt timeout.
4. Send the complete valid MP3 split across1031byte audio fragments and `turn.end`.

Configuration is replaced through a local control endpoint. This resets action position, while preserving monotonically increasing attempt IDs and per-speak attempt counts:

```powershell
$control = 'http://127.0.0.1:8765/control'
Invoke-RestMethod -Method Post -Uri $control -ContentType application/json -Body '{"actions":[{"type":"close","bytes":800}],"repeat_last":true}'
# After observing repeated retries, explicitly recover:
Invoke-RestMethod -Method Post -Uri $control -ContentType application/json -Body '{"actions":[{"type":"success"}]}'
```

Supported actions: `success` (optional `fragment_bytes`), `close` (optional `bytes`), `truncate` (optional `drop_bytes`), `stall` (optional `seconds`), `http_fail` (HTTP before upgrade, optional `status` 400-599, default 503), `no_audio` (`turn.end` without audio: the client must treat the fragment as declined, silent, with no retry). `repeat_last:true` repeats indefinitely until another control request. `repeat_last:false` switches to success after the list. In-flight actions keep their captured behavior; configure first, then send a new public client request. Do not send actual private text to this fixture.

Logs contain hashes only: global `attempt_id`, `chunk_attempt`, SHA256 of extracted SSML text, SHA256 of the `<speak>` body (voice/rate included), request-ID hash, timestamps, actions and byte counts. Transport headers change on retries, so compare speak/text hashes instead of whole WebSocket frame hashes. No raw request text, URL query token or SSML body is written. HTTP503 before SSML has no chunk hash; use application retry status for its attempt count.

```powershell
python -B tests/edge-fault-fixture/analyze.py build/edge-fixture/trace.jsonl --expected-completions 1
```

`--expected-hashes expected.json` additionally compares successful speak hashes in exact expected chunk order. Obtain expected hashes from a clean baseline of the same canned public text/voice/rate. Host `served_complete` means the server sent a response; it does not prove PCM was committed. Android integration must assert:

- No PCM commit, success callback, cached completed chunk, or text advancement after close, truncated end or stall.
- Recovery keeps the same pending speak/text hashes. With multi-chunk text, successful response hash sequence matches the clean baseline exactly; PCM sample counts/chunk commit counts match the clean run once each.
- Repeated transient failures survive beyond any former retry limit; control-triggered recovery eventually completes without an error callback.
- Stop during stall/retry promptly cancels the connection/wait, yields no late PCM, and produces the service's expected stop result.
- A replacement request after a stalled request uses its own identity and completes promptly; the cancelled request never emits late PCM.
- A long turn obeys the production compressed/decoded byte and duration bounds; codec/PCM validation tests cover oversized output and format rejection separately.

Host self-tests exercise the actual HTTP upgrade and WebSocket framing, partial/truncated/stalled/complete sequence, repeated503 and controlled recovery, stable text identity across changing request IDs, and absence of plaintext in trace output. They do not decode Android audio or claim emulator integration coverage.
