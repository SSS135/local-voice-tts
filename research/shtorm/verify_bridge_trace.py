"""Compare actual native token-ID logs with all4000 IDs and SentencePiece fixture sequences."""
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent / "bridge-proof"
expected = json.loads((ROOT / "expected.json").read_text())
trace_bytes = (ROOT / "native-probe.log").read_bytes()
raw = trace_bytes.decode("utf-8-sig", errors="replace")
actual = [[int(v) for v in row.split()] for row in re.findall(r"token_ids \(len=\d+\): ([0-9 ]+)", raw)]
assert len(actual) == len(expected), (len(actual), len(expected))
for index, (got, wanted) in enumerate(zip(actual, expected)):
    assert got == wanted, (index, got, wanted)
result = {"runtime": "sherpa-onnx1.13.8", "diagnosticOnly": True,
          "all4000IdsAndAll4000LeadingCompoundsExact": True, "sequencesVerified": len(actual),
          "traceSha256": hashlib.sha256(trace_bytes).hexdigest(), "traceHashEncoding": "raw-file-bytes",
          "note": "Constant diagnostic graphs isolate real native tokenizer; authentic audio tested separately."}
(ROOT / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
print(json.dumps(result))
