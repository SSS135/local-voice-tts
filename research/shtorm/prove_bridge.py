"""Exercise the real Sherpa tokenizer with diagnostic constant graphs, never speech models."""
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent
sys.path[:0] = [str(ROOT / "native-deps"), str(ROOT / "deps")]
import numpy as np
import onnx
from onnx import TensorProto as T, helper as h
import sherpa_onnx as sherpa

out = ROOT / "bridge-proof"
out.mkdir(exist_ok=True)


def constant_model(name, inputs, output_name, shape):
    value = h.make_tensor("value", T.FLOAT, shape, [0.0] * int(np.prod(shape)))
    node = h.make_node("Constant", [], [output_name], value=value)
    graph = h.make_graph([node], name, inputs, [h.make_tensor_value_info(output_name, T.FLOAT, shape)])
    model = h.make_model(graph, opset_imports=[h.make_opsetid("", 17)], ir_version=8)
    onnx.save(model, out / name)


constant_model("encoder.onnx", [h.make_tensor_value_info("audio", T.FLOAT, [1, 1, "T"])], "embedding", [1, 1, 1024])
constant_model("text.onnx", [h.make_tensor_value_info("tokens", T.INT64, [1, "T"])], "embedding", [1, 1, 1024])
constant_model("decoder.onnx", [h.make_tensor_value_info("latent", T.FLOAT, [1, "T", 32])], "audio", [1, 1, 1920])
constant_model("flow.onnx", [h.make_tensor_value_info("c", T.FLOAT, [1024]), h.make_tensor_value_info("s", T.FLOAT, [1, 1]),
                            h.make_tensor_value_info("t", T.FLOAT, [1, 1]), h.make_tensor_value_info("x", T.FLOAT, [1, 32])], "flow_dir", [1, 32])
main_inputs = [h.make_tensor_value_info("sequence", T.FLOAT, [1, "S", 32]),
               h.make_tensor_value_info("text_embeddings", T.FLOAT, [1, "T", 1024])]
nodes = [h.make_node("Constant", [], ["conditioning"], value=h.make_tensor("c", T.FLOAT, [1024], [0.0] * 1024)),
         h.make_node("Constant", [], ["eos_logit"], value=h.make_tensor("e", T.FLOAT, [1], [0.0]))]
graph = h.make_graph(nodes, "diagnostic-only-main", main_inputs,
                     [h.make_tensor_value_info("conditioning", T.FLOAT, [1024]), h.make_tensor_value_info("eos_logit", T.FLOAT, [1])])
onnx.save(h.make_model(graph, opset_imports=[h.make_opsetid("", 17)], ir_version=8), out / "main.onnx")
tts = sherpa.OfflineTts(sherpa.OfflineTtsConfig(model=sherpa.OfflineTtsModelConfig(
    pocket=sherpa.OfflineTtsPocketModelConfig(lm_flow=str(out / "flow.onnx"), lm_main=str(out / "main.onnx"),
        encoder=str(out / "encoder.onnx"), decoder=str(out / "decoder.onnx"), text_conditioner=str(out / "text.onnx"),
        vocab_json=str(ROOT / "onnx" / "bridge_vocab.json"), token_scores_json=str(ROOT / "onnx" / "bridge_token_scores.json")),
    num_threads=1, debug=True)))
config = sherpa.GenerationConfig()
config.reference_audio = np.zeros(1920, np.float32)
config.reference_sample_rate = 24000
config.num_steps = 1
config.silence_scale = 1.0
config.extra = {"max_frames": "1", "min_char_in_sentence": "1", "max_char_in_sentence": "20000"}
expected = [list(range(4000)), list(reversed(range(4000)))] + [[i] for i in range(4000)]
fixtures = json.loads((ROOT / "tokenizer-parity-fixtures.json").read_text(encoding="utf-8"))
expected.extend(case["ids"] for case in fixtures["fixtures"] if case["ids"])
(out / "expected.json").write_text(json.dumps(expected), encoding="utf-8")
for ids in expected:
    tts.generate("".join(chr(0xE000 + value) for value in ids), config)
print(json.dumps({"diagnosticOnly": True, "sequences": len(expected), "allIds": 4000}), flush=True)
