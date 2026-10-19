# tools/models

Model conversion/export kits and runtime pins. Not part of the Gradle build; consumed by source packaging (`SOURCE-PACKAGE.json` `workspaceFiles`), catalog pins and a few host tests. Native source snapshots and upstream licences live in `third_party/native-source` and `third_party/licenses`.

| Path | Role | Consumers |
|---|---|---|
| `shtorm/` | Shtorm Russian export/packaging scripts, licences, `runtime-manifest.json` (SHA-256/size of the files pinned in the app catalog), tokenizer parity fixtures, pinned upstream source zips, past-run result JSONs | pins and packaging; `onnx/` (untracked model files) feeds `ShtormBpeCheck` (`gradlew test`) |
| `pocket-en/` | Pocket English v2 export script, model card (release asset, pinned), manifest and pins, host eval, frontend proofs | pins and packaging |
| `silero-ru/` | Silero v5_6_ru ONNX/frontend export scripts, pins (`runtime-manifest.json`), host validation and Java parity fixture | pins, packaging; `frontend-parity.json` feeds `SileroFrontendTest` |

Fetch native sources (verifies SHA-256 and size; zips are gitignored), from the repo root:

```powershell
.\third_party\native-source\fetch-sources.ps1
```

Model inputs: `shtorm/download-inputs.ps1`, `pocket-en/download-inputs.ps1`, `silero-ru/download-inputs.ps1`; steps in each `REPRODUCE.md`. Storage profiles (`tests/profile-storage-*.ps1`) expect untracked `tools/models/kokoro-extract/` and `tools/models/supertonic-direct/` datasets.
