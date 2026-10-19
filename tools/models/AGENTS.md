# tools/models: agent rules

Map and fetch commands: `README.md`. Repo-wide rules: `../../AGENTS.md`.

- Never commit model weights, exported ONNX, audio or downloaded inputs; only manifests, scripts, proofs and licences.
- A pin change here (SHA-256 + size in `*manifest.json`) must match the app catalog entry and vice versa.
- Files uploaded as release assets (`pocket-en/README.md`, `pocket-en/LICENSE`, `shtorm/ATTRIBUTION.txt`, `shtorm/LICENSE-CC-BY-4.0.txt`, `shtorm/voice-provenance.json`, `silero-ru/ATTRIBUTION.txt`, `silero-ru/LICENSE-CC-BY-NC-SA-4.0.txt`) are pinned by hash; editing them breaks the catalog pins.
- Adding, moving or removing a tracked file: update `workspaceFiles`/`sourceArchives` in `SOURCE-PACKAGE.json`, then run `tests\run-source-package-check.ps1`.
- `shtorm/*-source.zip`/`pocket-export-base.zip` are pinned by hash in `SOURCE-PACKAGE.json`; never modify them.
- Python: `uv run python -m <module>` or `uv run python <script>.py`; pinned deps in `shtorm/requirements-export.txt`.
