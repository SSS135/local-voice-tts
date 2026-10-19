# research: agent rules

Map and fetch commands: `README.md`. Repo-wide rules: `../AGENTS.md`.

- Never commit model weights, exported ONNX, audio or downloaded inputs; only manifests, scripts, proofs and licences.
- A pin change here (SHA-256 + size in `*manifest.json`) must match the app catalog entry and vice versa.
- Adding, moving or removing a tracked file: update `workspaceFiles`/`sourceArchives` in `LocalVoice/SOURCE-PACKAGE.json`, then run `LocalVoice\tests\run-source-package-check.ps1`.
- `native-source/*.zip` and `shtorm/*-source.zip`/`pocket-export-base.zip` are pinned by hash in `SOURCE-PACKAGE.json`; never modify them.
- `pocket-native/` is historical reference; do not edit to change runtime behaviour (the AAR is built from the sherpa-onnx snapshot).
- Python: `uv run python -m <module>` or `uv run python <script>.py`; pinned deps in `shtorm/requirements-export.txt`.
