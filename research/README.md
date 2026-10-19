# research

Provenance for models and native code. Not part of the Gradle build; consumed by source packaging (`LocalVoice/SOURCE-PACKAGE.json` `workspaceFiles`), catalog pins and a few host tests.

| Path | Role | Status |
|---|---|---|
| `native-source/` | pinned sherpa-onnx, piper-phonemize, eSpeak NG source snapshots (`source-manifest.json`), upstream build files, `BUILD-AND-SOURCE-NOTES.txt` | live: GPL source obligation; zips required by `LocalVoice/tests/run-source-package-check.ps1` |
| `licenses/` | upstream licence texts (eSpeak NG, piper-phonemize, sherpa-onnx, ONNX Runtime, Supertonic) | live: packaged; `SUPERTONIC-UPSTREAM-LICENSE` used by `LocalVoice/tests/profile-storage-hashes.ps1` |
| `shtorm/` | Shtorm Russian export/packaging scripts, licences, `runtime-manifest.json` (SHA-256/size of the files pinned in the app catalog), tokenizer parity fixtures, pinned upstream source zips | live: pins and packaging; `onnx/` (untracked model files) feeds `ShtormBpeCheck` (`LocalVoice/gradlew test`) |
| `pocket-en/` | Pocket English v2 export script, model card, manifest and pins, host eval, frontend proofs | live: pins and packaging |
| `silero-ru/` | Silero v5_6_ru ONNX/frontend export scripts, pins (`runtime-manifest.json`), host validation and Java parity fixture | live: pins and packaging |
| `pocket-native/` | sherpa-onnx Pocket C++ sources read during integration | historical: duplicates files inside the sherpa-onnx zip in `native-source/` |

Fetch native sources (verifies SHA-256 and size; zips are gitignored):

```powershell
.\research\native-source\fetch-sources.ps1
```

Model inputs: `shtorm/download-inputs.ps1`, `pocket-en/download-inputs.ps1`, `silero-ru/download-inputs.ps1`; steps in each `REPRODUCE.md`. Storage profiles expect untracked `research/kokoro-extract/` and `research/supertonic-direct/` datasets.
