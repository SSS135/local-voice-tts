# Pocket English v2: Kyutai `english_2026-09_2` fixed-Alba INT8 pack

Shipped as catalog entry `pocket-en-v2` (revision `kyutai-english-2026-09-2-onnx-int8-v1`); files are assets of GitHub release `pocket-en-model-v2`. `runtime-manifest.json` holds the source pins, the per-file size/SHA-256/URL contract (must match `app/src/main/resources/org/localvoice/tts/models.json`) and the Alba recovery errors. The previous soniqo export of checkpoint `tts_b6369a24` (`pocket-en-alba`) and its validation files are in git history before this change.

## Compatibility with the sherpa-onnx 1.13.8 `pocket` graph layout

Checked against `kyutai-labs/pocket-tts@1df6b3da` `pocket_tts/config/english_2026-09_2.yaml` and the checkpoint tensors: same modules and dims as the shipped graphs and as Shtorm (FlowLM d_model 1024, 16 heads, 6 layers, LSD flow head depth 6/dim 512 with s/t inputs, 32-d latents, Mimi 24 kHz/12.5 Hz, 4000-piece tokenizer with byte-identical `tokenizer.model`). Differences from `tts_b6369a24`: `insert_bos_before_voice: true` (handled by baking the learned BOS into the encoder output, as for Shtorm), Mimi `inner_dim` 32, no `pad_with_spaces_for_short_inputs` (the app never padded), defaults temperature 0.3 / 1 LSD step. `english_drifting_26-09` was not used (different flow head). Model and module code of the exporter base `41cbc84` equals `1df6b3da` except voice-pin metadata.

The open repo zeroes `mimi.encoder.*`, so the Alba prompt cannot be re-encoded from audio (the gated repo returned 403). `export_v2.py` instead recovers the 127×1024 prompt (BOS + speaker-projected latents) from Kyutai's precomputed Alba KV state: per-row least squares on layer-0 values, then a per-row scale search on layer-1 values. The voice affects generation only through that KV cache; the recovered prompt reproduces all six layers to ≤1.4e-5 max abs error in PyTorch and ≤6e-6 through the exported fp32 `flow_lm_main.onnx`.

## Rebuild

Python 3.11 venv from `../shtorm/requirements-export.txt` plus `ml_dtypes==0.5.1` (needed by `onnxruntime.quantization`), outside the repo:

```powershell
uv venv --python 3.11 $env:TEMP\pocket-v2\venv
$env:VIRTUAL_ENV="$env:TEMP\pocket-v2\venv"; uv pip install -r ..\shtorm\requirements-export.txt ml_dtypes==0.5.1 --index-strategy unsafe-best-match
& $env:TEMP\pocket-v2\venv\Scripts\python.exe -I export_v2.py $env:TEMP\pocket-v2\work
```

Downloads (pinned URL, size, SHA-256 in `export_v2.py`): checkpoint, Alba state and tokenizer from `kyutai/pocket-tts-without-voice-cloning@ab248779` (checkpoint blob identical to the config pin `575d1437`), exporter `lookbe/pocket-tts-onnx-export@2b25e5a`, Kyutai sources `41cbc84` and `1df6b3da` (the three ZIPs are reused from `../shtorm` when present). Output: `work/pack/` (the 10 release files), `pack-manifest.json`, `export-result.json`, stage logs. Two runs on one machine produced byte-identical files; `vocab.json`/`token_scores.json` are byte-identical to the soniqo v1 tables. Graph bytes across other torch/onnx versions are not promised; hash the actual output.

Quantization: lookbe `scripts/quantize.py` (`quantize_dynamic`, QInt8 per-channel): `lm_main` transformer projections, `lm_flow` MatMul/Gemm, decoder except upsampling convolutions. `text_conditioner.onnx` and the constant `encoder.onnx` stay float32, as in v1.

## Host validation

`host_eval.py synth OUT NAME PACK STEPS TEMP` (export venv) runs sherpa-onnx 1.13.8 with the app's generation extras on 11 sentences (4 questions) × seeds 1, 2; `host_eval.py asr OUT` (venv with `faster-whisper==1.1.1 jiwer==3.1.0`) transcribes with Whisper small.en. Results in `host-eval-result.json`:

| Pack / config | WER | exact | median first audio | median RTF |
|---|---|---|---|---|
| v2, 1 step, T 0.3 (shipped) | 0.0% | 22/22 | 1.2–1.7 s | 0.48–0.66 |
| v2, 4 steps, T 0.7 | 0.42% | 21/22 | 1.8 s | 0.71 |
| v1 soniqo, 4 steps, T 0.7 | 0.42% | 21/22 | 1.8–4.0 s | 0.73–1.24 |

Ranges are two runs on a desktop CPU loaded by other jobs; sherpa 1.13.8 emits PCM only after a chunk's latents are generated, so first audio ≈ chunk compute. Round-trip ASR measures intelligibility, not naturalness.

`download-inputs.ps1` fetches and verifies the released files from `runtime-manifest.json`; `frontend_probe.py` / `frontend-proof/` remain valid because the tokenizer is unchanged.
