"""Create a strict import-only Shtorm runtime pack; never include diagnostic graphs or raw checkpoint."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "deps"))
import onnx

RUNTIME = ["flow_lm_flow.onnx", "flow_lm_main.onnx", "mimi_encoder.onnx", "mimi_decoder.onnx", "text_conditioner.onnx",
           "vocab.json", "token_scores.json", "normalizer.bin", "bridge_vocab.json", "bridge_token_scores.json", "voice-vctk-p228-24k.wav"]
FILES = {name: ROOT / "onnx" / name for name in RUNTIME}
FILES.update({name: ROOT / name for name in ["LICENSE-CC-BY-4.0.txt", "ATTRIBUTION.txt", "voice-provenance.json"]})


def sha(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(8 * 1024 * 1024):
            result.update(chunk)
    return result.hexdigest()


entries = [{"path": name, "size": path.stat().st_size, "sha256": sha(path)} for name, path in FILES.items()]
manifest = {"schemaVersion": 1, "id": "shtorm-pocket-ru-fast", "name": "Shtorm PocketTTS Russian v2 fast",
            "backend": "sherpa-pocket", "version": "2026-10-01-fast-onnx-fp32-v1", "languages": ["ru-RU"],
            "license": "CC-BY-4.0", "delivery": "import_only", "downloadUrl": None,
            "downloadUnavailableReason": "Author hosts safetensors; locally converted runtime graphs have not been published.",
            "modelSource": {"repo": "ArtShtorm/Shtorm-PocketTTS-RU", "revision": "33f6c328d62c4da5c1227bbe081e89b574a6183a",
                            "file": "model_fast.safetensors", "size": 438033312,
                            "sha256": "de8ad576d3d549372a34b40df03d6e6b8f668e857dac32f61934e9c5c1da8d52"},
            "config": {"lmFlow": "flow_lm_flow.onnx", "lmMain": "flow_lm_main.onnx", "encoder": "mimi_encoder.onnx",
                       "decoder": "mimi_decoder.onnx", "textConditioner": "text_conditioner.onnx", "vocabJson": "bridge_vocab.json",
                       "tokenScoresJson": "bridge_token_scores.json", "bpeVocab": "vocab.json", "bpeScores": "token_scores.json",
                       "bpeNormalizer": "normalizer.bin", "referenceAudio": "voice-vctk-p228-24k.wav", "referenceSampleRate": "24000",
                       "numThreads": "2"},
            "voices": [{"id": "vctk-p228", "name": "VCTK p228 reference", "sid": 0,
                        "language": "ru-RU", "referenceAudio": "voice-vctk-p228-24k.wav", "license": "CC-BY-4.0"}],
            "files": entries, "totalBytes": sum(row["size"] for row in entries)}
text = json.dumps(manifest, indent=2, ensure_ascii=False)
(ROOT / "runtime-manifest.json").write_text(text, encoding="utf-8")
io = {}
for name in RUNTIME:
    if not name.endswith(".onnx"):
        continue
    model = onnx.load(FILES[name], load_external_data=False)
    onnx.checker.check_model(model)
    def value(item):
        tensor = item.type.tensor_type
        return {"name": item.name, "elementType": tensor.elem_type,
                "shape": [d.dim_param or d.dim_value for d in tensor.shape.dim]}
    io[name] = {"inputs": [value(i) for i in model.graph.input], "outputs": [value(i) for i in model.graph.output],
                "opsets": [{"domain": o.domain, "version": o.version} for o in model.opset_import]}
    for item in io[name]["inputs"]:
        if item["name"].startswith("state_"):
            assert item["elementType"] in (1, 7, 9), item
    del model
(ROOT / "graph-io.json").write_text(json.dumps(io, indent=2), encoding="utf-8")
destination = ROOT / "shtorm-pocket-ru-fast-onnx-fp32-v1.zip"
with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_STORED) as pack:
    for name, path in FILES.items():
        info = zipfile.ZipInfo(name, date_time=(2026, 10, 18, 0, 0, 0))
        info.external_attr = 0o644 << 16
        pack.writestr(info, path.read_bytes())
    pack.writestr("manifest.json", text.encode("utf-8"))
with zipfile.ZipFile(destination) as pack:
    assert set(pack.namelist()) == set(FILES) | {"manifest.json"}
    for row in entries:
        assert hashlib.sha256(pack.read(row["path"])).hexdigest() == row["sha256"]
summary = {"pack": destination.name, "size": destination.stat().st_size, "sha256": sha(destination),
           "runtimeFiles": len(entries), "runtimeBytes": manifest["totalBytes"], "delivery": "import_only"}
(ROOT / "pack-result.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
print(json.dumps(summary), flush=True)
