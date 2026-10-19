# Authentic Shtorm conversion and host validation

Run these commands from this directory in PowerShell. Use Python 3.11, a CPU-only virtual environment, and a few GB of free disk/RAM. No Android emulator, browser, service upload, training, or external publication is involved. Downloads are explicit model/source inputs only. The checkpoint is the author's Russian fast v2 model, not an English substitute.

```powershell
py -3.11 -m venv .venv
$python = Join-Path $PWD '.venv\Scripts\python.exe'
& $python -m pip install -r requirements-export.txt
.\download-inputs.ps1
& $python -B prepare_config.py
& $python -B export_local.py mimi
& $python -B export_local.py flow
& $python -B tokenizer_fixtures.py
& $python -B bridge_vocab.py
& $python -B prepare_voice.py
& $python -B verify_encoder.py
& $python -B reference.py
& $python -B native_smoke.py *> native-bridge-float32-attempt.log
& $python -B prove_bridge.py *> bridge-proof\native-probe.log
& $python -B verify_bridge_trace.py
& $python -B package_model.py
```

Create `bridge-proof` before redirecting its log on a clean tree (`New-Item -ItemType Directory -Force bridge-proof`). `tokenizer_fixtures.py` also exports `normalizer.bin`; no separate extractor is required. `prepare_config.py` resolves checkpoint/tokenizer paths in the pinned author configuration without changing architecture. The source manifests contain immutable URLs, lengths and SHA256 values; download verification is mandatory. The three source ZIPs can be supplied alongside the scripts to avoid fetching those inputs again. The model checkpoint and voice recording must be downloaded separately and are excluded from the source distribution.

The normal route uses the active venv. `export_local.py` and `reference.py` accept optional `--torch-path` for reusing an existing CPU-only package cache; `verify_encoder.py` accepts `LOCALVOICE_TORCH_PATH` for the same optional purpose. No developer-specific path is required. Existing `deps`/`native-deps` directories take precedence only in this research workspace; omit them from a portable checkout. Both `sherpa-onnx` and matching `sherpa-onnx-core` 1.13.8 are required: mixing old ORT DLLs with its extension fails the native API check.

The conversion uses lookbe exporter 2b25e5a09d7b243edc4bbff7589363dc57a0204e and its exact full Kyutai source 41cbc84af539ea78a804ffca5f9c6edc1a22ce44. Its incomplete top-level `pocket_tts` copy is not imported. The unmodified reference uses Kyutai 1df6b3da17cb43eff8c4c4e077f785520492adff. `export_local.py` makes two explicit compatibility changes: float32 recurrent states (Sherpa 1.13.8 aborts on float16 state allocation) and one learned BOS-before-voice prefix baked into Mimi encoder output. Legacy full KV cache capacity is 1000; no weights are trained or replaced.

Shtorm's SentencePiece model is BPE. Its raw vocabulary cannot be handed directly to Sherpa's unigram tokenizer. The app must run the genuine embedded normalization map and BPE merges first, then pass token IDs as U+E000+ID symbols. Native receives `bridge_vocab.json` and `bridge_token_scores.json`; the original vocabulary/scores/map remain separate app frontend assets. The 8000-key bridge consumes native's dummy prefix together with the first ID. Diagnostic constant graphs from `prove_bridge.py` are test-only and must never enter the model pack, catalog, or APK. The recorded exhaustive run verified all 4000 IDs, all singleton first-token compounds, and genuine SentencePiece fixtures: 4160 sequences. The app Java frontend separately passed 160 normalization cases/2401 assertions.

Recorded evidence: `reference-result.json` proves original PyTorch PCM; `encoder-parity-result.json` compares authentic prompt embeddings and text lookup (encoder max error about 1.1e-6); export logs contain decoder/state/flow numerical parity; `native-result.json` proves authentic Sherpa PCM; `bridge-proof/result.json` proves native token identity. These are separate checks. Whole-wave PyTorch/C++ sample equality is not asserted because random generation paths differ. Graph export byte identity across Python/ONNX versions is not promised; the generated manifest always hashes the actual output.

`package_model.py` checks all five graphs, allows only 14 runtime/license/provenance files, and creates the import-only ZIP with per-file hashes. It excludes checkpoint, source archives, test graphs, logs and venvs. There is no hosted converted Shtorm artifact: the app must explicitly import this pack rather than offer a dead download or download safetensors it cannot execute. The recorded pack is 440049147 bytes, SHA256 `8b9892ae5f14539535d8d803a0161e4382ce1e590f4661efc7c180acc139ae88`.

Model and derived graphs: CC-BY-4.0, ArtShtorm/Kyutai attribution. The sole tested prompt is a bounded, resampled VCTK p228 public reference from pinned Kyutai voices; its CC-BY-4.0 attribution, enhancement, crop/fade/resampling changes and original hash are in `voice-provenance.json` and `ATTRIBUTION.txt`. It is not an author's built-in Russian voice. Exporter and Pocket source MIT notices are retained. See the separate native source kit for Sherpa/eSpeak GPL corresponding-source duties.

Native limitations measured on the host: Pocket ignores `GenerationConfig.speed` (the Android adapter needs the separately tested rate processor), and PCM callbacks start after latent generation. Callback cancellation suppresses later PCM but cannot immediately interrupt the latent CPU loop. Bound chunks/context and device cancellation/replacement tests are required. Android validation remained paused during these host experiments; host success is not an Android performance claim.

Portable source allowlist: all named Python/PowerShell scripts above, `requirements-export.txt`, `python-environment.json`, `source-input-manifest.json`, `runtime-manifest.json`, `graph-io.json`, `voice-provenance.json`, tokenizer fixture/result JSON, small result summaries, full CC/MIT license files, `ATTRIBUTION.txt`, this document and the three pinned source ZIPs. Exclude `deps`, `native-deps`, `.venv`, `onnx`, checkpoint, tokenizer binary, voice/audio files, model-pack ZIP, raw large debug traces and generated diagnostic graphs. Source pins/download recipe regenerate excluded assets.
