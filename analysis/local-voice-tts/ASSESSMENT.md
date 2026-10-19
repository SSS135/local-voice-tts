# Local Voice — assessment (2026-10-19)

Scope: HEAD `a4a804c` (247 tracked files) plus uncommitted Edge/UI/Shtorm fixes. Goal: restructure for agentic development. Tools: `scc` 3.x, grep-based dependency matrix, git blob-hash comparison. No telemetry available.

## Executive summary

Android system TTS engine (Java 17, Gradle 9.5.1, sherpa-onnx JNI, optional Microsoft Edge online voices), ~13.4 KSLOC of source. Runtime code is well hardened (pinned downloads, safe import, bounded parsers). The repo is hard for agents: tests run only through 18 Windows-only PowerShell runners (Gradle `test` runs nothing), 371 model pins live in one Java file, dense multi-statement lines, docs/licences scattered, no AGENTS.md/CI/formatter. Recommendation: in-place refactor of layout and tooling; keep `LocalVoice/` as Gradle root.

## System inventory

| Language | Files | Code lines | Complexity |
|---|---:|---:|---:|
| Java | 88 | 7,250 | 2,078 |
| C++ / headers | 10 | 2,642 | 686 |
| Python | 25 | 1,349 | 382 |
| YAML | 4 | 776 | 0 |
| PowerShell | 26 | 641 | 163 |
| Shell / CMake / Batch / Gradle | 9 | 748 | 229 |
| JSON (data) | 25 | 11,593 | 0 |
| Text / Markdown / licences | 46 | 5,825 | 0 |

Highest complexity: `ModelStore.java` (177), `EdgeBackend.java` (130), `ModelFiles.java` (110), `LocalVoiceService.java` (101), `ShtormBpeFrontend.java` (86), `MainActivity.java` (82).

Fingerprint: Gradle root `LocalVoice/` (`settings.gradle`, modules `app`, `probe`); deps `LocalVoice/app/build.gradle:28` (local sherpa-onnx 1.13.8 AAR fetched by `fetch-runtime.ps1`, okhttp 5.4.0, kotlin-stdlib 2.3.21); storage `noBackupFilesDir/models/<id>` + SharedPreferences `voices`; integrations: Android `TTS_SERVICE`, HTTPS model downloads (Hugging Face, GitHub release `shtorm-model-v1`), Edge Read Aloud WSS; tests: `main()`-style checks (no JUnit), `androidTest` probes, Python e2e/fault fixture.

## Architecture at a glance

Diagram: [ARCHITECTURE.mmd](ARCHITECTURE.mmd). `M/` = `LocalVoice/app/src/main/java/org/localvoice/tts/`, `B/` = `M/backend/`.

| Domain | Key files | Tests |
|---|---|---|
| D1 Service / TTS API | `M/LocalVoiceService`, `CheckVoiceDataActivity`, `InstallVoiceDataActivity`, `LocaleTags`, `SpeechRatePolicy` | device only; `tests/SettingsPolicyTest.java` orphaned |
| D2 Backend coordinator | `B/SpeechBackend`, `SerializedBackend`, `BackendRegistry`, `SherpaBackend` | `run-backend-contract.ps1` |
| D3 Local backends | `B/Kokoro*`, `Supertonic*`, `Pocket*`, `Shtorm*`, `RussianCardinals` | `run-shtorm-text.ps1`, `run-shtorm-bpe.ps1` (needs untracked models) |
| D4 Edge online | `B/Edge*`, `M/EdgeCatalogRepository`, `assets/edge-voices-snapshot.json` | `run-edge-*.ps1`, Python fault fixture |
| D5 Audio | `B/PcmTimeStretch`, `B/Sonic` (generated from `third_party/androidx-media3-sonic`) | `run-pcm-time-stretch.ps1` |
| D6 Catalog + storage | `M/Catalog`, `ModelStore`, `ModelFiles`, `DownloadFile`, `SecureModelPackImporter` | 7 storage runners + `tests/storage-host` Android stubs |
| D7 UI | `M/MainActivity`, `AboutActivity`, `Ui`, `OnlineCatalogUi`, `VoiceFilter` | `run-ui-state.ps1` |
| D8 Build/packaging | `build.ps1`, `fetch-runtime.ps1`, `package-source.ps1`, `SOURCE-PACKAGE.json` | `run-source-package-check.ps1` |
| D9 Verification | `app/src/test`, `app/src/androidTest`, `tests/**`, `probe/` | — |
| D10 Research/provenance | `research/{native-source,pocket-en,pocket-native,shtorm}` | not in build; consumed only by packaging and pins |
| D11 Docs/legal | 9 `.md` + 5 licence files at `LocalVoice/` root, 14 in `assets/{legal,licenses}` | — |

Implicit couplings: `Catalog.MODELS` is a mutable static rewritten by the Edge path; prefs keys are string literals shared by `VoicePreferences` and `LocalVoiceService`; `NativeBenchInstrumentation` reflectively mutates private `BackendRegistry.FACTORIES`.

## Production runtime profile

No telemetry available. Emulator measurement (Edge fix): Chromium's ~900-locale availability probe took 281 s before the fix, 1.1–1.6 s after it.

## Technical debt (ranked)

1. Host tests not runnable via Gradle: `main()` checks, no JUnit; runners hand-pick sources and compile `--release 8` vs app Java 17 (`tests/run-storage-store.ps1:9-11`).
2. Runners fragile: orphan `SettingsPolicyTest`; README lists 7 of 18; `;` classpaths; Gradle-cache scraping (`run-backend-contract.ps1:16-29`); unfetched `downloads/android-json-test.jar`.
3. Model catalog hardcoded: 371 `ModelFile` rows in `Catalog.java:69-447`, line 446 is 4.5k chars; `profile-storage-hashes.ps1` regex-parses Java.
4. Dense style, no formatter: `MainActivity.java` 495 statements / 241 lines; lines up to 1,449 chars; no `.editorconfig`/Spotless.
5. Dead SAF import path (no catalog entry uses `installMode=import` after Shtorm moved to download): `ModelStore.java:67-77,367-444`, `SecureModelPackImporter`, `MainActivity` import UI.
6. `ModelStore` god object: 14 static fields, HTTP client, staging, verification, SAF grants, metrics, one executor for all jobs.
7. Error-handling gaps: silent catches at `ModelStore.java:439`, `AboutActivity.java:17`.
8. Windows-only build/fetch (`curl.exe`, `gradlew.bat`, `javac.exe`); no `.gitattributes`, `core.autocrlf=true`; no CI.
9. Licence/notice files duplicated 3–5×; exclusion list kept in 3 places (`SOURCE-PACKAGE.json`, `package-source.ps1:11-13`, both `.gitignore`).
10. Packaging manifest drift: `SOURCE-PACKAGE.json` omits root `README.md`, `LICENSE`, `.gitignore`, `LocalVoice/fetch-runtime.ps1`; hand-maintained research list.

## Security findings

No hardcoded project secrets; tree and history are clean (commit identities are GitHub noreply).

| ID | CWE | Sev | Evidence | Fix |
|---|---|---|---|---|
| SEC-001 | CWE-787 / CWE-1395 | Medium | espeak-ng `7251208e` (pre-Dec-2023 CVE-2023-49990..49994 fixes) per `research/native-source/source-manifest.json:21-28`, reached via Kokoro from any app binding the TTS service | Upgrade/rebuild sherpa-onnx with fixed espeak-ng; meanwhile cap word length, strip control/PUA chars for Kokoro. Binary not verified. |
| SEC-002 | CWE-489 | Medium | `app/build.gradle:23` applies release signing to debuggable `debug`; `build.ps1` defaults to `assembleDebug`; debug manifest allows cleartext | Never sign debug with release key; signing flow must use `assembleRelease`. |
| SEC-003 | CWE-798 | Low | `B/EdgeProtocol.java:14` public Edge client token `6A5A****` (not project-owned) | Treat as public config; document breakage risk. |
| SEC-004 | CWE-359 | Low | `LocalVoiceService` language fallback can route text to Microsoft; `KEY_FEATURE_EMBEDDED_SYNTHESIS` ignored | Honour embedded-only requests; use online voices only when named or default. |
| SEC-005 | CWE-494 | Low | AAR not hash-checked at build time; no `gradle/verification-metadata.xml` | Gradle dependency verification + AAR SHA task. |
| SEC-006 | CWE-22 | Low | `probe/.../ProbeActivity.java:49-53` builds file name from intent extra | Restrict to `[A-Za-z0-9_-]{1,64}`. |
| SEC-007 | CWE-295 | Info | No pinning for Edge WSS | Optional. |

## Documentation gaps

1. No agent entry point: build/test/lint commands and prerequisites (AAR, JSON jar, JDK path, untracked research inputs) are undeclared.
2. Voice-selection policy (locale → voice, selected-voice preference, online fallback) was undocumented until the Edge fix.
3. `research/` has no README: which material feeds pins/packaging vs is historical (`research/pocket-native` duplicates the sherpa zip).
4. Docs cite gitignored `evidence/` (`VERIFICATION-PROFILE.md:38,46,57,102`, `UI-DESIGN.md:3`).
5. `ARCHITECTURE.md` mixes the backend contract with release-verification narrative.

## Relative scale

Source KSLOC 13.4 (code excluding JSON/text/licences). COCOMO-II index = 2.94 × 13.4^1.10 ≈ **51**. This is a relative size measure for comparison only, not a timeline or cost estimate.

## Recommended pattern

**Refactor in place** (same stack, no rewrite). Runtime design is sound; the debt is layout, tooling and test plumbing. Execute as a direct restructure (target layout below), not `/modernize-uplift` (no version bump) or `/modernize-transform`.

Target layout (keeps `LocalVoice/` as Gradle root; flattening would break the source-package `LocalVoice/` prefix and every `$workspace` path):

```
/
├── AGENTS.md                 commands, layout map, rules
├── README.md  LICENSE  .gitignore (merged)  .gitattributes  .editorconfig
├── .github/workflows/ci.yml  fetch AAR, gradlew assembleDebug test lint, sonic --check
├── LocalVoice/               Gradle root
│   ├── README.md  LICENSE  gradle files  build.ps1 fetch-runtime.ps1 package-source.ps1 SOURCE-PACKAGE.json
│   ├── docs/                 ARCHITECTURE EDGE-CATALOG SHTORM-FRONTEND SOURCE-PACKAGING UI-DESIGN
│   │   └── history/          RELEASE-1.1.1 VERIFICATION-PROFILE ENGLISH-POCKET-REVIEW
│   ├── legal/                top-level licence/notice texts
│   ├── app/src/test/java/…   existing checks + tests/*.java, JUnit-wrapped
│   ├── hosttest/             JVM module: storage-host Android stubs + ModelStore checks
│   ├── probe/  third_party/
│   └── tests/                e2e/ edge-fault-fixture/ fixtures/ profile-*.ps1
└── research/
    ├── AGENTS.md  README.md
    ├── licenses/
    └── native-source/ pocket-en/ pocket-native/ shtorm/
```
