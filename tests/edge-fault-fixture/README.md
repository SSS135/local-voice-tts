# Edge host fault fixture

Test source only; Python3 standard library, with optional local ffmpeg for generating audio. Nothing in this directory is an Android production endpoint, exported activity, or release asset. The test APK's `NativeBenchInstrumentation` (`mode=edge-fixture`) injects `new EdgeBackend("http://127.0.0.1:8765/edge",2000L)` into the app process; the production factory keeps its real endpoint.

## Emulator suite (`tests/e2e/run_fault_suite.py`)

Disposable emulator only (`LOCALVOICE_TEST_SERIAL`, see `tests/e2e/README.md`). From the repository root:

```powershell
& tests/edge-fault-fixture/prepare-audio.ps1 -Seconds 3 -OutputDirectory build/edge-fixture/stream
.\gradlew.bat :app:assembleDebug :probe:assembleDebug
.\gradlew.bat :app:assembleDebugAndroidTest '-PtestRunner=org.localvoice.tts.NativeBenchInstrumentation'   # quoted: PowerShell splits -Pa=b.c
adb -s $serial install -r -t app/build/outputs/apk/debug/app-debug.apk   # also the androidTest and probe APKs
# Enable online voices and select an Edge voice in the app (a selected local voice is preloaded on bind and delays the first request).
adb -s $serial reverse tcp:8765 tcp:8765
uv run python -B tests/edge-fault-fixture/server.py --mp3 build/edge-fixture/stream/valid.mp3 --log evidence/reliability/fixture-trace.jsonl
adb -s $serial shell am instrument -r -w -e mode edge-fixture -e durationMs 7200000 org.localvoice.tts.test/org.localvoice.tts.NativeBenchInstrumentation
uv run python -B tests/e2e/run_fault_suite.py [case ...]   # all cases by default; writes evidence/reliability/fault-suite-summary.json
```

Stop: `adb shell run-as org.localvoice.tts touch files/edge-fixture-stop` (restores the factory), stop the server, `adb reverse --remove tcp:8765`.

| Case | Fault | Required behaviour |
|---|---|---|
| `baseline` | none, 3 runs | one attempt each, identical PCM |
| `tail_handoff_next_transient` | 2 requests of 2 paragraphs (`speak`, next after `onDone`); request 2: spare 503 at handshake, 2 failed attempts | request 2 claims request 1's tail; done, no error |
| `tail_handoff_next_fatal` | request 2: HTTP 400 | tail handed first (`firstPcm`), then one error; 2 handshakes, no retry storm |
| `tail_handoff_next_declined` | request 2's first paragraph: `turn.end` without audio on the spare | tail claimed; paragraph silent; next paragraph plays; done |
| `drop_mid_stream` | abort after 7200 bytes and 9 words | retry resumes at the spoken word (text suffix hash), not the paragraph start |
| `decline_after_partial_audio` | close 1007 after audio | rest of the fragment skipped; one attempt; done |
| `decline_before_audio` | `turn.end` without audio | silent, one attempt, done |
| `spare_closed_before_use` | spare closed (1001) before paragraph 2 | paragraph 2 on a fresh connection, no failed attempt |
| `spare_stale` | spare accepts SSML, never answers | one attempt timeout, then fresh connection; done |
| `outage_guard` | 5 paragraphs, all declined | paragraphs 1-3 once each; 4th retried with growing backoff until recovery |
| `fault_sequence` | abort at 800 bytes, truncated last frame, stall | recovers; truncated end accepted (incomplete frame dropped) |
| `slow_stream` | audio paced over 6.4 s, attempt timeout 2 s | one attempt: the timeout is inactivity, not turn length |
| `stop_during_streaming` | paced stream, stop on first PCM | stop < 2 s, no late PCM, no further attempt |
| `seek_during_streaming` | paced stream, `stop()` + `speak()` of the next paragraph | old request stopped, new one done |
| `repeated_recovery` | 503 × 7, then success | pending without PCM, then done |
| `stop_backoff_and_inflight` | stop during 503 backoff / stalled attempt | stop < 2 s, no later attempt |
| `auth_retry_once` | 403 then success; 403 always | one retry; fatal after exactly 2 handshakes |
| `replacement` | stall, then replacement request with another voice | replacement done, old one stopped |

## Server

```powershell
& tests/edge-fault-fixture/prepare-audio.ps1            # 1 s 440 Hz tone, mono 24 kHz, MP3 48 kbps; -Seconds n for longer
uv run python -B -m unittest discover -s tests/edge-fault-fixture -p test_server.py -v
uv run python -B tests/edge-fault-fixture/server.py --mp3 build/edge-fixture/valid.mp3 --log build/edge-fixture/trace.jsonl --scenario tests/edge-fault-fixture/faults.json
uv run python -B tests/edge-fault-fixture/analyze.py build/edge-fixture/trace.jsonl --expected-completions 1
```

Generated audio, traces and provenance stay under `build/edge-fixture`; they are excluded from source delivery. `audio-provenance.json` records source kind, MP3 hash, reference PCM hash and sizes. ffmpeg versions may encode different bytes; compare a run against its own baseline. Android codec output can differ numerically from ffmpeg's `reference.pcm`.

The server binds only 127.0.0.1. GET `/health` reports readiness, MP3 hash and request count. Use a 2000 ms attempt timeout so stalls fail promptly.

Each WebSocket upgrade takes the next action when it connects, before any SSML: the client's pre-warmed spare connection (opened at a turn's first audio) consumes one action and applies it to the next turn sent on it. `http_fail` fails the handshake. POST `/control` replaces the configuration, resets the action position (attempt IDs and per-speak attempt counts keep counting) and closes idle connections (1001) so the next request starts on a connection under the new configuration:

```powershell
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8765/control -ContentType application/json -Body '{"actions":[{"type":"close","bytes":800}],"repeat_last":true,"words":true}'
```

Actions: `success` (`fragment_bytes`, `fragment_delay` seconds between fragments), `close` (`bytes` of audio, may be 0; after `seconds`, abort, or send close `code`, e.g. 1007 = declined), `truncate` (`drop_bytes`), `stall` (`seconds`), `http_fail` (`status` 400-599, default 503), `no_audio` (`turn.end` without audio: declined), `idle_close` (close 1001 after `seconds` without serving SSML). `repeat_last:true` repeats the last action until the next control request; `false` continues with `success`. `words:true` sends `audio.metadata` WordBoundary messages for the SSML words, spread evenly over the full tone and interleaved before the audio that reaches them. Default `faults.json`: abort after 800 bytes; all but the last byte then `turn.end`; 6 s stall; success in 1031-byte fragments.

Trace rows (JSONL) hold hashes only: `upgrade` (connection, action), `attempt` (attempt and connection IDs, `chunk_attempt` per speak hash, SHA-256 of SSML text, of the `<speak>` body and of the request ID), `served_complete`/`served_truncated`/`served_no_audio`, `mid_response_close` (`bytes_sent`, `words_sent`, `code`), `stalled`, `peer_cancelled`, `client_closed`, `idle_closed`, `http_fail`, `disconnected`. A resumed retry sends a suffix of the text: match its `text_sha256` against the hashes of the text's word suffixes. `served_complete` means the server sent a response, not that PCM was committed. Do not send private text to this fixture.

Host self-tests cover HTTP upgrade and WebSocket framing, the partial/truncated/stalled/complete sequence, repeated 503 and controlled recovery, word metadata, close codes, idle close, stable text identity across request IDs and absence of plaintext in the trace. They do not decode audio or claim emulator coverage.
