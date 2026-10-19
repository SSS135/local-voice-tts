"""Export a lossless token-ID alphabet for Sherpa's internal unigram tokenizer."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
vocab = {}
for token_id in range(4000):
    symbol = chr(0xE000 + token_id)
    vocab[symbol] = token_id
    # Consume Sherpa's dummy prefix together with the first ID; no extra embedding row.
    vocab["▁" + symbol] = token_id
scores = {piece: 0.0 for piece in vocab}
out = ROOT / "onnx"
(out / "bridge_vocab.json").write_text(json.dumps(vocab, indent=2, ensure_ascii=False), encoding="utf-8")
(out / "bridge_token_scores.json").write_text(json.dumps(scores, indent=2, ensure_ascii=False), encoding="utf-8")
print("Exported8000keys,4000IDs; pass bare PUA symbols with no manually added space/prefix.")
