# Hosted English PocketTTS fixed-Alba validation

This is the soniqo July 14 2026 INT8 export at immutable Hugging Face revision `ad5d3fae3fb2f10024583708597afca8337c094c`. It derives from historical Kyutai checkpoint `tts_b6369a24` at `d4fdd22ae8c8e1cb3634e150ebeff1dab2d16df3`, not the newer September checkpoint. It contains one fixed Alba MacKenna voice. Model and voice are CC-BY-4.0; the downloaded full LICENSE and README preserve attribution and modifications. Additional attribution and source/exporter pins are in `runtime-manifest.json`. See `LocalVoice/docs/history/ENGLISH-POCKET-REVIEW.md` for the independent historical license check.

Use the Python 3.11 CPU venv/dependency versions described in sibling `shtorm/REPRODUCE.md`. The matching `sherpa-onnx-core==1.13.8` is essential. From this directory:

```powershell
.\download-inputs.ps1
python -B prepare_manifest.py
python -B inspect_assets.py
python -B native_smoke.py *> native-smoke.log
python -B frontend_probe.py
```

Ten published files were measured and matched their published SHA256: eight runtime files total 126152659 bytes, plus full LICENSE and README total 126176567. All download URLs use the immutable revision. `manifest.json` is the publisher's provenance/hash manifest; `runtime-manifest.json` is our verified download contract. The current-main `model-api.json` is exploratory metadata and must not be treated as pin metadata or included in the source pack.

The actual tokenizer is 4000-piece UNIGRAM with byte fallback and identity normalization, suitable for Sherpa's native unigram route; it is unrelated to the Shtorm BPE/PUA route. Native host logs for `Hello world.` contain exactly the genuine SentencePiece IDs `[2994,578,263]`. A separate test-only constant-graph probe passed 32/32 native ID vectors against genuine SentencePiece, including contractions, accented letters, unknown-script byte fallback and normalized whitespace (`frontend-proof/result.json`). The probe reuses the diagnostic graph builder from sibling `shtorm/prove_bridge.py`; its graphs are excluded from runtime assets. Raw SentencePiece whitespace normalization occurs before native encoding in this probe. Android must implement equivalent whitespace preparation. Native sentence splitting treats a decimal period as a sentence boundary; decimal numeric expansion remains a frontend decision rather than proof of unchanged whole-string tokenization. All graph state inputs are float32/int64/bool, not float16. Local ONNX 1.17 checker cannot inspect newer IR version 13 on one graph; this tool-version limitation is recorded in `inspection-result.json`. The actual Sherpa 1.13.8/ORT 1.28.2 runtime successfully loads and runs all graphs, without graph modification.

The English normalizer has `add_dummy_prefix=true`, **`remove_extra_whitespaces=false`**, no precompiled character map and no Unicode NFKC conversion. Preserve internal spaces, tabs and NBSP; unfamiliar characters use byte fallback. The probe strips outer ASCII spaces only after genuine identity normalization. Do not reuse Shtorm's normalization or collapse arbitrary Unicode whitespace. This is the exact preparation used for the 32 passing vectors.

`native-result.json` records finite, nonempty 24kHz PCM, real generation latency, callback stop, and deterministic hashes. The native API requires a nonempty audio reference. Supply 1920 float32 zeros at 24kHz: the tiny fixed encoder ignores the input. A different dummy reference produced identical PCM, confirming this is a fixed voice rather than cloning. There is no separate voice WAV to download or license.

Native speed 1 and 1.5 produced identical PCM; the Android adapter must use the separately tested rate processor. Returning zero from the first callback returned one 1920-sample block; callback delivery starts after latent generation, so this does not prove immediate compute cancellation. Android public-service stop/replacement, memory and CPU validation remain the engine owner's device test gate. These host tests do not establish speech quality superiority.

Portable source allowlist: this file, `download-inputs.ps1`, `prepare_manifest.py`, `inspect_assets.py`, `native_smoke.py`, `frontend_probe.py`, `frontend-proof/result.json`, `frontend-proof/expected.json`, `manifest.json`, `runtime-manifest.json`, `inspection-result.json`, `native-result.json`, LICENSE and upstream README. Exclude all ONNX/tokenizer binaries, audio, diagnostic graphs/raw debug logs, local dependency trees and exploratory `model-api.json`. Downloads regenerate the exact model assets; no publication or paid service is required.
