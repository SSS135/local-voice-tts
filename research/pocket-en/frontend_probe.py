"""Probe real native unigram IDs on normalized English cases using test-only constant graphs."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
SHARED = ROOT.parent / "shtorm"
sys.path[:0] = [str(SHARED / "native-deps"), str(SHARED / "deps")]
import sentencepiece as spm

out = ROOT / "frontend-proof"
out.mkdir(exist_ok=True)
if "--probe" not in sys.argv:
    with (out / "native-probe.log").open("w", encoding="utf-8") as log:
        subprocess.run([sys.executable, "-B", str(Path(__file__).resolve()), "--probe"], stdout=log, stderr=log, check=True)
    rows = json.loads((out / "expected.json").read_text(encoding="utf-8"))
    trace_bytes = (out / "native-probe.log").read_bytes()
    raw = trace_bytes.decode("utf-8", errors="replace")
    actual = [[int(v) for v in row.split()] for row in re.findall(r"token_ids \(len=\d+\): ([0-9 ]+)", raw)]
    assert len(actual) == len(rows), (len(actual), len(rows))
    failures = [{"text": row["text"], "expected": row["ids"], "actual": got} for row, got in zip(rows, actual) if row["ids"] != got]
    result = {"runtime": "sherpa-onnx1.13.8", "diagnosticOnly": True, "sequences": len(rows), "exact": len(rows)-len(failures),
              "failures": failures, "normalization": "Genuine SentencePiece identity normalization, whitespace markers changed back to ASCII spaces before native encoding",
              "traceSha256": hashlib.sha256(trace_bytes).hexdigest(), "traceHashEncoding": "raw-file-bytes"}
    (out / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result))
    raise SystemExit(1 if failures else 0)

# Reuse only the diagnostic graph builder, stopping before that script's tokenizer/runtime test.
# These constant graphs exercise native tokenization without generation or recurrent-cache limits.
builder_path = SHARED / "prove_bridge.py"
builder = builder_path.read_text(encoding="utf-8").split("tts = sherpa.OfflineTts", 1)[0]
builder = builder.replace('ROOT = Path(__file__).resolve().parent', 'ROOT = SHARED')
builder = builder.replace('out = ROOT / "bridge-proof"', 'out = ROOT_EN / "frontend-proof"')
namespace = {"__file__": str(builder_path), "SHARED": SHARED, "ROOT_EN": ROOT}
exec(compile(builder, str(builder_path), "exec"), namespace)
import numpy as np
import sherpa_onnx as sherpa
tts = sherpa.OfflineTts(sherpa.OfflineTtsConfig(model=sherpa.OfflineTtsModelConfig(
    pocket=sherpa.OfflineTtsPocketModelConfig(lm_flow=str(out / "flow.onnx"), lm_main=str(out / "main.onnx"),
        encoder=str(out / "encoder.onnx"), decoder=str(out / "decoder.onnx"), text_conditioner=str(out / "text.onnx"),
        vocab_json=str(ROOT / "vocab.json"), token_scores_json=str(ROOT / "token_scores.json")), num_threads=1, debug=True)))
config = sherpa.GenerationConfig()
config.reference_audio = np.zeros(1920, np.float32)
config.reference_sample_rate = 24000
config.num_steps = 1
config.silence_scale = 1.0
config.extra = {"max_frames": "1", "min_char_in_sentence": "1", "max_char_in_sentence": "4096"}
sp = spm.SentencePieceProcessor(model_file=str(ROOT / "tokenizer.model"))
cases = ["Hello world.", "HELLO WORLD.", "Hello", "hello", "world", "A", "a", "I", "can't", "don't", "we're", "it's",
         "one two three", "12345", "English pronunciation", "Alba MacKenna", "a  b", "  a b  ", "a\tb\nc",
         "caf\u00e9", "na\u00efve", "Stra\u00dfe", "e\u0301", "\U0001f600", "\u4e2d\u6587", "\u041f\u0440\u0438\u0432\u0435\u0442", "a\u00a0b", "a\u2009b", "a-b", "state-of-the-art", "test@example", "(hello)"]
rows = []
for raw in cases:
    plain = sp.normalize(raw).replace("\u2581", " ").strip()
    rows.append({"text": raw, "prepared": plain, "ids": sp.encode(plain, out_type=int)})
    tts.generate(plain, config)
(out / "expected.json").write_text(json.dumps(rows, indent=2, ensure_ascii=False), encoding="utf-8")
