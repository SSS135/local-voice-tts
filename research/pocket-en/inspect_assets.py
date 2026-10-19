"""Verify every published English pack hash and inspect real graph/tokenizer contracts."""
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent / "shtorm" / "deps"))
import onnx
import sentencepiece as spm
from sentencepiece import sentencepiece_model_pb2 as pb

manifest = json.loads((ROOT / "manifest.json").read_text())
checks = []
for name, expected in manifest["files"].items():
    path = ROOT / name
    data = path.read_bytes()
    assert len(data) == expected["bytes"], name
    actual = hashlib.sha256(data).hexdigest()
    assert actual == expected["sha256"], name
    checks.append({"path": name, "size": len(data), "sha256": actual})
sp = spm.SentencePieceProcessor(model_file=str(ROOT / "tokenizer.model"))
proto = pb.ModelProto()
proto.ParseFromString((ROOT / "tokenizer.model").read_bytes())
graphs = {}
for path in ROOT.glob("*.onnx"):
    model = onnx.load(path)
    checker_error = None
    try:
        onnx.checker.check_model(model)
    except onnx.checker.ValidationError as error:
        if "ir_version" not in str(error):
            raise
        checker_error = str(error)
    def describe(value):
        t = value.type.tensor_type
        return {"name": value.name, "elementType": t.elem_type,
                "shape": [d.dim_param or d.dim_value for d in t.shape.dim]}
    graphs[path.name] = {"irVersion": model.ir_version, "localCheckerLimitation": checker_error,
                         "inputs": [describe(v) for v in model.graph.input],
                         "outputs": [describe(v) for v in model.graph.output]}
    unsupported = [i for i in graphs[path.name]["inputs"] if i["name"].startswith("state_") and i["elementType"] not in (1, 7, 9)]
    assert not unsupported, unsupported
    del model
result = {"repo": "soniqo/Pocket-TTS-100M-ONNX-INT8", "revision": "ad5d3fae3fb2f10024583708597afca8337c094c",
          "publishedFileChecks": checks, "tokenizer": {"modelType": pb.TrainerSpec.ModelType.Name(proto.trainer_spec.model_type),
          "vocabSize": sp.get_piece_size(), "byteFallback": proto.trainer_spec.byte_fallback,
          "normalizer": proto.normalizer_spec.name, "charsmapBytes": len(proto.normalizer_spec.precompiled_charsmap),
          "addDummyPrefix": proto.normalizer_spec.add_dummy_prefix,
          "removeExtraWhitespaces": proto.normalizer_spec.remove_extra_whitespaces,
          "escapeWhitespaces": proto.normalizer_spec.escape_whitespaces,
          "treatWhitespaceAsSuffix": proto.trainer_spec.treat_whitespace_as_suffix}, "graphs": graphs}
(ROOT / "inspection-result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
print(json.dumps({"verifiedFiles": len(checks), "tokenizer": result["tokenizer"],
                  "stateInputCounts": {n: sum(v["name"].startswith("state_") for v in g["inputs"]) for n, g in graphs.items()}}))
