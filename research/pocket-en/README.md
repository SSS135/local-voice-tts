---
license: cc-by-4.0
language:
- en
base_model: kyutai/pocket-tts-without-voice-cloning
library_name: onnxruntime
pipeline_tag: text-to-speech
---

# Pocket TTS English `english_2026-09_2` — fixed Alba, ONNX INT8 (Local Voice)

ONNX export of Kyutai's Pocket TTS checkpoint `english_2026-09_2` (Kyutai's default `english` model since 2026-10-11) for the sherpa-onnx 1.13.8 `pocket` runtime used by the Local Voice Android app. English only; one fixed voice (Alba MacKenna); no voice cloning.

| File | Content | Precision |
|---|---|---|
| `lm_main.int8.onnx` | Transformer backbone + EOS, float32 KV states (capacity 1000) | dynamic per-channel INT8 MatMul (transformer projections) |
| `lm_flow.int8.onnx` | LSD flow head | dynamic per-channel INT8 MatMul/Gemm |
| `decoder.int8.onnx` | Streaming Mimi decoder | dynamic INT8, upsampling convolutions kept float32 |
| `text_conditioner.onnx` | Token embedding table | float32 |
| `encoder.onnx` | Constant Alba voice prompt (127×1024); ignores its audio input | float32 |
| `vocab.json`, `token_scores.json`, `tokenizer.model` | Kyutai 4000-piece unigram SentencePiece tokenizer and tables derived from it | — |

24 kHz mono float PCM. Recommended generation: temperature 0.3, 1 LSD step (Kyutai defaults).

## Attribution and licence (CC BY 4.0)

- Model: Pocket TTS by Kyutai Labs, `kyutai/pocket-tts-without-voice-cloning@ab248779e0718743c013e20b2d812ace7c8f4bf7`, `languages/english_2026-09_2/model.safetensors` (SHA-256 `e106c451108e066119f070eb7cf6c9ff4a2ad93f3bf04c9914cc94c66b7a4eeb`). CC BY 4.0: https://creativecommons.org/licenses/by/4.0/ (full text in `LICENSE`).
- Voice: Alba MacKenna, CC BY 4.0, https://huggingface.co/kyutai/tts-voices#alba-mackenna. Kyutai's precomputed Alba state for this checkpoint: `languages/english_2026-09_2/embeddings/alba.safetensors` (SHA-256 `4206b7194018c7e345e5cfd99f147caad36285df858ea153d0188c40d9f261e5`).
- Modifications by the Local Voice project: converted to ONNX opset 17 with lookbe/pocket-tts-onnx-export@2b25e5a (MIT) on Kyutai pocket-tts@41cbc84 (MIT), float32 recurrent states; dynamic INT8 weight quantization of three graphs; the voice prompt (learned BOS + speaker projection) was numerically recovered from Kyutai's Alba KV state and baked into `encoder.onnx` as a constant; tokenizer tables exported from `tokenizer.model`. No weights were trained or changed other than by quantization.

Reproduction recipe, pins and hashes: https://github.com/SSS135/local-voice-tts/tree/main/research/pocket-en (`REPRODUCE.md`, `export_v2.py`, `runtime-manifest.json`).
