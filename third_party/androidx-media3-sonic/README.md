# Vendored Sonic core

Source: AndroidX Media3 **1.5.1**, immutable commit
`76088cd6af7f263aba238b7a48d64bd4f060cb8b`.

The original `Sonic.java` and Apache 2.0 `LICENSE` are retained here. The production
copy is under `app/src/main/java/org/localvoice/tts/backend/Sonic.java`. Its only
changes are the package, a standalone state assertion, and widening two AMDF
comparison products to `long`. The latter fixes integer overflow demonstrated by
the 96 kHz / 220 Hz regression case. Copyright and modification notices are
retained in the source and `legal/THIRD_PARTY_SONIC_NOTICES.txt`.

Run `uv run python third_party/androidx-media3-sonic/update-source.py` from the project
root to fetch the exact pinned source, verify its SHA-256, and reproduce the
adaptation. This is an explicit maintenance command and is not part of a build.
Add `--check` to verify the retained source hashes, adapted production source,
and packaged license offline without changing files or accessing the network.
`PcmTimeStretchCheck` (run by `gradlew test`) holds the host checks. They synthesize their own
220 Hz fixtures in memory; no model weights, audio uploads, browser, or emulator
is needed.

`PcmTimeStretch` uses pitch 1 and identical input/output sample rates. Speed 1
preserves float samples exactly. Other speeds use Sonic's 16-bit core, with
bounded 2048-sample input and output blocks and per-utterance cancellation.
The measured host fixtures cover 8–96 kHz, 0.5–2.0 speed, exact duration changes,
preserved tone frequency, arbitrary chunk boundaries, short/empty input,
repeated finish, cancelled/rejected/throwing output, and retained-buffer bounds.
Native Pocket integration and Android audio verification are separate checks.
