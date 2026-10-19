# Local Voice: agent guide

Android system TTS engine `org.localvoice.tts` (Java 17, Gradle, sherpa-onnx JNI): downloadable local voices (Kokoro, Supertonic, Pocket, Shtorm) plus opt-in online Microsoft Edge voices. GPL-3.0.

## Layout

| Path | Purpose |
|---|---|
| `LocalVoice/` | Gradle root (modules `app`, `probe`); do not flatten: source packages and scripts use the `LocalVoice/` prefix |
| `LocalVoice/app/src/main` | engine, backends (`java/org/localvoice/tts/backend`), UI, bundled licence assets |
| `LocalVoice/app/src/test`, `LocalVoice/hosttest` | JVM host tests |
| `LocalVoice/app/src/androidTest`, `LocalVoice/probe` | device instrumentation and the public TTS client probe app |
| `LocalVoice/tests` | e2e drivers (`e2e/`), Edge fault fixture, fixtures, storage profiling scripts, source-package check |
| `LocalVoice/docs` | current design docs; start with `docs/ARCHITECTURE.md` (backend contract) |
| `LocalVoice/docs/history` | release and verification evidence: immutable |
| `LocalVoice/legal` | top-level licence and notice texts shipped in source packages |
| `LocalVoice/third_party` | vendored upstream sources (Sonic) and their update scripts |
| `research/` | model/native provenance, pin inputs, licences; see `research/AGENTS.md` |
| `analysis/` | restructure assessment; not shipped |

## Prerequisites

- JDK 17+ (Android Studio JBR works), Android SDK Platform 36 (`ANDROID_HOME` or `local.properties`).
- `cd LocalVoice; .\fetch-runtime.ps1` once: fetches and verifies the sherpa-onnx 1.13.8 AAR into `app/libs/` (gitignored).
- Source-package check needs native source zips: `.\research\native-source\fetch-sources.ps1`.

## Commands (PowerShell)

```powershell
cd LocalVoice
.\gradlew.bat :app:assembleDebug                      # build
.\build.ps1 -JavaHome '<jdk>' -AndroidSdk '<sdk>'     # build with explicit JDK/SDK
.\gradlew.bat test                                    # all host checks (app/src/test + hosttest); ./gradlew test on Unix; ShtormBpeCheck skips without research/shtorm/onnx
.\gradlew.bat :app:lint                               # lint
.\tests\run-source-package-check.ps1                  # source-package allowlist check; writes no ZIP
.\package-source.ps1 -ListOnly                        # list source-package members
```

Device/e2e: `LocalVoice/tests/e2e/README.md` (disposable emulator only; Edge fault suite needs `tests/edge-fault-fixture`).

## Rules

- Never commit model weights, keys, keystores, APKs/AABs, recordings or downloads; `.gitignore` is the list.
- Every model file is pinned by SHA-256 and byte size; downloads are HTTPS-only and verified against the pin before install.
- Edge online voices are opt-in. Text must never reach Microsoft unless the user enabled online voices and selected an Edge voice; no silent fallback.
- Keep catalog licence and attribution fields and `assets/{legal,licenses}` texts intact when changing models.
- `LocalVoice/docs/history/` is evidence: fix broken links only, never rewrite content.
- Moving or adding shipped files: update `LocalVoice/SOURCE-PACKAGE.json` and run the source-package check.
- Other agents may edit this checkout concurrently: re-read before editing, make targeted edits, never revert changes you did not make.
- Python: `uv run python -m <module>` (`uv run python <script>` for scripts that are not modules).
- Agent instructions live in `AGENTS.md` files; do not add `CLAUDE.md`.
