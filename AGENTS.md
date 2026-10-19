# Local Voice: agent guide

Android system TTS engine `org.localvoice.tts` (Java 17, Gradle, sherpa-onnx JNI): downloadable local voices (Kokoro, Supertonic, Pocket, Shtorm) plus opt-in online Microsoft Edge voices. GPL-3.0.

## Layout

Repository root = Gradle root (modules `app`, `probe`, `hosttest`). Source ZIPs store every member as `LocalVoice/<repo path>`; see `docs/SOURCE-PACKAGING.md`.

| Path | Purpose |
|---|---|
| `app/src/main` | engine, backends (`java/org/localvoice/tts/backend`), UI, bundled licence assets |
| `app/src/test`, `hosttest` | JVM host tests |
| `app/src/androidTest`, `probe` | device instrumentation and the public TTS client probe app |
| `tests` | e2e drivers (`e2e/`), Edge fault fixture, fixtures, storage profiling scripts, source-package check |
| `docs` | current design docs; start with `docs/ARCHITECTURE.md` (backend contract) |
| `docs/history` | release and verification evidence: immutable |
| `legal` | top-level licence and notice texts shipped in source packages |
| `third_party/androidx-media3-sonic` | vendored Sonic source and its update script |
| `third_party/native-source` | pinned sherpa-onnx, piper-phonemize, eSpeak NG source snapshots (`source-manifest.json`), upstream build files; GPL source obligation |
| `third_party/licenses` | upstream licence texts (eSpeak NG, piper-phonemize, sherpa-onnx, ONNX Runtime, Supertonic) |
| `tools/models` | model conversion/export kits and runtime pins (Shtorm, Pocket English, Silero); see `tools/models/AGENTS.md` |
| `analysis/` | restructure assessment; not shipped |

## Prerequisites

- JDK 17+ (Android Studio JBR works), Android SDK Platform 36 (`ANDROID_HOME` or `local.properties`).
- `.\fetch-runtime.ps1` once: fetches and verifies the sherpa-onnx 1.13.8 AAR into `app/libs/` (gitignored).
- Source-package check needs native source zips: `.\third_party\native-source\fetch-sources.ps1`.

## Commands (PowerShell, repo root)

```powershell
.\gradlew.bat :app:assembleDebug                      # build
.\build.ps1 -JavaHome '<jdk>' -AndroidSdk '<sdk>'     # build with explicit JDK/SDK
.\gradlew.bat test                                    # all host checks (app/src/test + hosttest); ./gradlew test on Unix; ShtormBpeCheck skips without tools/models/shtorm/onnx
.\gradlew.bat :app:lint                               # lint
.\tests\run-source-package-check.ps1                  # source-package allowlist check; writes no ZIP
.\package-source.ps1 -ListOnly                        # list source-package members
```

Device/e2e: `tests/e2e/README.md` (disposable emulator only; Edge fault suite needs `tests/edge-fault-fixture`).

## Rules

- Never commit model weights, keys, keystores, APKs/AABs, recordings or downloads; `.gitignore` is the list.
- Every model file is pinned by SHA-256 and byte size; downloads are HTTPS-only and verified against the pin before install.
- Edge online voices are opt-in. Text must never reach Microsoft unless the user enabled online voices and selected an Edge voice; no silent fallback.
- Keep catalog licence and attribution fields and `assets/{legal,licenses}` texts intact when changing models.
- `docs/history/` is evidence: fix broken links only, never rewrite content.
- Moving or adding shipped files: update `SOURCE-PACKAGE.json` and run the source-package check.
- `third_party/native-source/*.zip` and `tools/models/shtorm/*.zip` are pinned by hash in `SOURCE-PACKAGE.json`; never modify them.
- Other agents may edit this checkout concurrently: re-read before editing, make targeted edits, never revert changes you did not make.
- Python: `uv run python -m <module>` (`uv run python <script>` for scripts that are not modules).
- Agent instructions live in `AGENTS.md` files; do not add `CLAUDE.md`.
