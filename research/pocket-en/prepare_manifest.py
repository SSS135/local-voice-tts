"""Verify published checksums and produce direct-download catalog data without altering weights."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parent
published = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
revision = "ad5d3fae3fb2f10024583708597afca8337c094c"
base = "https://huggingface.co/soniqo/Pocket-TTS-100M-ONNX-INT8/resolve/" + revision + "/"
runtime = ["decoder.int8.onnx", "encoder.onnx", "lm_flow.int8.onnx", "lm_main.int8.onnx", "text_conditioner.onnx", "token_scores.json", "tokenizer.model", "vocab.json"]
files = []
for name in runtime + ["LICENSE", "README.md"]:
    data = (root / name).read_bytes()
    expected = published["files"][name]
    assert len(data) == expected["bytes"] and hashlib.sha256(data).hexdigest() == expected["sha256"], name
    files.append({"path": name, "size": len(data), "sha256": expected["sha256"], "url": base + name})
result = {"schemaVersion": 1, "id": "pocket-en-alba", "name": "PocketTTS English — Alba (July 2026 export)",
          "backend": "sherpa-pocket", "version": "soniqo-2026-07-14-ad5d3fa", "languages": ["en-US", "en-GB"],
          "license": "CC-BY-4.0", "delivery": "direct_files", "repository": "soniqo/Pocket-TTS-100M-ONNX-INT8", "revision": revision,
          "source": published["model"], "exporter": published["exporter"], "frontend": "native-unigram",
          "config": {"lmFlow": "lm_flow.int8.onnx", "lmMain": "lm_main.int8.onnx", "encoder": "encoder.onnx",
                     "decoder": "decoder.int8.onnx", "textConditioner": "text_conditioner.onnx", "vocabJson": "vocab.json",
                     "tokenScoresJson": "token_scores.json", "fixedVoice": "true", "referenceSampleRate": "24000", "numThreads": "2"},
          "referenceContract": {"mode": "fixed_alba", "generatedDummySamples": 1920, "sampleRate": 24000,
                                "value": 0, "reason": "Native API requires nonempty reference; this pack's encoder ignores its audio input."},
          "voices": [{"id": "alba", "name": "Alba MacKenna", "sid": 0, "language": "en-GB", "license": "CC-BY-4.0"}],
          "attribution": "PocketTTS by Kyutai; fixed Alba MacKenna voice (CC-BY-4.0); ONNX INT8 conversion by soniqo. Converted/quantized from historical checkpoint tts_b6369a24, not the September 2026 checkpoint. https://huggingface.co/kyutai/tts-voices#alba-mackenna",
          "files": files, "runtimeFileCount": len(runtime), "runtimeBytes": sum(f["size"] for f in files if f["path"] in runtime),
          "totalBytes": sum(f["size"] for f in files)}
(root / "runtime-manifest.json").write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
print(json.dumps({"verifiedPublishedFiles": len(files), "runtimeBytes": result["runtimeBytes"], "totalBytes": result["totalBytes"]}))
