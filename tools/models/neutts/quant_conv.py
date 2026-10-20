# NeuCodec decoder: quantize the 9 fp32 Conv1d weights of the shipped int8 decoder (docs/NEUTTS-OPTIMIZATION.md item 6,
# quality gate docs/TTS-QUANT-QUALITY.md case b). Everything else in the graph is left byte-identical.
#   mm      each Conv -> pad/slice/concat im2col + the upstream dynamic-int8 MatMul pattern (DynamicQuantizeLinear,
#           MatMulInteger, Cast, Mul, Add), which ORT_ENABLE_ALL fuses into DynamicQuantizeMatMul (U8S8 udot on ARM);
#           int8 symmetric weights, per output channel (default) or per tensor (--per-tensor)
#   mmfp32  same rewrite with fp32 MatMul weights (exactness check of the rewrite)
#   convint ORT quantize_dynamic on the convs: DynamicQuantizeLinear + ConvInteger, u8 per-tensor weights
#   qdq     ORT quantize_static on the convs: QDQ, per-channel s8 weights, u8 activations calibrated on --calib codes
#   fp16    conv weights stored fp16 + Cast (file size only: ORT constant-folds the Cast back to fp32 at load)
# --keep embed,post_net.1.conv2 leaves convs whose weight name contains any listed substring in fp32.
# uv run --no-project --python 3.12 --with onnx==1.19.1 --with onnxruntime==1.28.0 --with numpy python -I tools/models/neutts/quant_conv.py IN.onnx OUT.onnx mm [--keep ...] [--per-tensor] [--calib codes.jsonl]
# uv run ... quant_conv.py check FP32.onnx   (rewrite exactness on the fp32 upstream decoder; on the int8 one, dynamic
#   activation quantization amplifies float rounding differences)
import argparse, json, sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

INT64_MAX = np.iinfo(np.int64).max


def convs(model, keep):
    inits = {i.name: i for i in model.graph.initializer}
    out = []
    for n in model.graph.node:
        if n.op_type == "Conv" and n.input[1] in inits and not any(k in n.input[1] for k in keep):
            out.append(n)
    return out, inits


def const(name, array):
    return numpy_helper.from_array(np.asarray(array), name)


def rewrite_mm(model, keep, quantize, per_channel):
    g = model.graph
    targets, inits = convs(model, keep)
    for n in targets:
        attrs = {a.name: helper.get_attribute_value(a) for a in n.attribute}
        assert attrs.get("group", 1) == 1 and attrs.get("strides", [1]) == [1] and attrs.get("dilations", [1]) == [1], n.name
        w = numpy_helper.to_array(inits[n.input[1]]).astype(np.float32)  # [O, C, k]
        o, c, k = w.shape
        p0, p1 = attrs["pads"]
        assert p0 + p1 == k - 1, n.name
        x, y, p = n.input[0], n.output[0], n.name + "_im2col"
        nodes, new_inits = [], []
        if k > 1:
            new_inits.append(const(p + "_pads", np.array([0, 0, p0, 0, 0, p1], np.int64)))
            nodes.append(helper.make_node("Pad", [x, p + "_pads"], [p + "_padded"], name=p + "_pad"))
            new_inits.append(const(p + "_axis", np.array([2], np.int64)))
            for j in range(k):
                new_inits += [const(f"{p}_s{j}", np.array([j], np.int64)),
                              const(f"{p}_e{j}", np.array([j - (k - 1) if j < k - 1 else INT64_MAX], np.int64))]
                nodes.append(helper.make_node("Slice", [p + "_padded", f"{p}_s{j}", f"{p}_e{j}", p + "_axis"], [f"{p}_tap{j}"],
                                              name=f"{p}_slice{j}"))
            nodes.append(helper.make_node("Concat", [f"{p}_tap{j}" for j in range(k)], [p + "_cols"], axis=1, name=p + "_concat"))
            cols = p + "_cols"
        else:
            cols = x
        nodes.append(helper.make_node("Transpose", [cols], [p + "_rows"], perm=[0, 2, 1], name=p + "_t_in"))  # [1, T, k*C]
        wm = w.transpose(2, 1, 0).reshape(k * c, o)  # row j*C + c holds W[:, c, j]
        if quantize:
            amax = np.abs(wm).max(axis=0) if per_channel else np.abs(wm).max()
            scale = (np.maximum(amax, 1e-12) / 127.0).astype(np.float32)
            q = np.clip(np.round(wm / scale), -127, 127).astype(np.int8)
            new_inits += [const(p + "_wq", q), const(p + "_wscale", scale)]
            nodes += [
                helper.make_node("DynamicQuantizeLinear", [p + "_rows"], [p + "_xq", p + "_xs", p + "_xz"], name=p + "_dql"),
                helper.make_node("MatMulInteger", [p + "_xq", p + "_wq", p + "_xz"], [p + "_acc"], name=p + "_mmi"),
                helper.make_node("Cast", [p + "_acc"], [p + "_accf"], to=TensorProto.FLOAT, name=p + "_cast"),
                helper.make_node("Mul", [p + "_xs", p + "_wscale"], [p + "_scale"], name=p + "_scales_mul"),
                helper.make_node("Mul", [p + "_accf", p + "_scale"], [p + "_mm"], name=p + "_scale_mul"),
            ]
        else:
            new_inits.append(const(p + "_w", wm))
            nodes.append(helper.make_node("MatMul", [p + "_rows", p + "_w"], [p + "_mm"], name=p + "_mm"))
        if len(n.input) > 2 and n.input[2]:
            nodes.append(helper.make_node("Add", [p + "_mm", n.input[2]], [p + "_biased"], name=p + "_bias"))
            last = p + "_biased"
        else:
            last = p + "_mm"
        nodes.append(helper.make_node("Transpose", [last], [y], perm=[0, 2, 1], name=p + "_t_out"))
        at = list(g.node).index(n)
        g.node.remove(n)
        for i, m in enumerate(nodes):
            g.node.insert(at + i, m)
        g.initializer.remove(inits[n.input[1]])
        g.initializer.extend(new_inits)
    return model


def rewrite_fp16(model, keep):
    targets, inits = convs(model, keep)
    for n in targets:
        w = inits[n.input[1]]
        h = numpy_helper.from_array(numpy_helper.to_array(w).astype(np.float16), w.name + "_fp16")
        model.graph.initializer.remove(w)
        model.graph.initializer.append(h)
        cast = helper.make_node("Cast", [h.name], [w.name], to=TensorProto.FLOAT, name=w.name + "_cast")
        model.graph.node.insert(list(model.graph.node).index(n), cast)
    return model


class Codes:
    """Calibration reader: real backbone code sequences (one per jsonl line, key "codes")."""
    def __init__(self, path, name, limit):
        seqs = [json.loads(line)["codes"] for line in open(path, encoding="utf-8")]
        self.items = iter([{name: np.array(s, np.int32).reshape(1, 1, -1)} for s in seqs[:limit] if len(s) > 1])

    def get_next(self):
        return next(self.items, None)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("mode", choices=["mm", "mmfp32", "convint", "qdq", "fp16"])
    ap.add_argument("--keep", default="")
    ap.add_argument("--per-tensor", action="store_true")
    ap.add_argument("--calib")
    ap.add_argument("--calib-limit", type=int, default=64)
    a = ap.parse_args()
    keep = [k for k in a.keep.split(",") if k]
    if a.mode in ("convint", "qdq"):
        from onnxruntime.quantization import QuantFormat, QuantType, quantize_dynamic, quantize_static
        names = [n.name for n in convs(onnx.load(a.src), keep)[0]]
        if a.mode == "convint":
            quantize_dynamic(a.src, a.dst, op_types_to_quantize=["Conv"], nodes_to_quantize=names, weight_type=QuantType.QUInt8)
        else:
            inp = onnx.load(a.src, load_external_data=False).graph.input[0].name
            quantize_static(a.src, a.dst, Codes(a.calib, inp, a.calib_limit), quant_format=QuantFormat.QDQ,
                            op_types_to_quantize=["Conv"], nodes_to_quantize=names, per_channel=True,
                            activation_type=QuantType.QUInt8, weight_type=QuantType.QInt8)
        return
    model = onnx.load(a.src)
    model = rewrite_fp16(model, keep) if a.mode == "fp16" else rewrite_mm(model, keep, a.mode == "mm", not a.per_tensor)
    onnx.save(model, a.dst)


def check(src):
    """The im2col rewrite with fp32 weights must reproduce the fp32 decoder's convs."""
    import os, tempfile
    import onnxruntime as ort
    dst = os.path.join(tempfile.mkdtemp(), "mmfp32.onnx")
    onnx.save(rewrite_mm(onnx.load(src), [], False, True), dst)
    rng = np.random.default_rng(0)
    for f in (2, 5, 31):
        codes = rng.integers(0, 65536, (1, 1, f)).astype(np.int32)
        a, b = (ort.InferenceSession(p, providers=["CPUExecutionProvider"]).run(None, {"codes": codes})[0] for p in (src, dst))
        err = np.abs(a - b).max() / np.abs(a).max()
        assert a.shape == b.shape and err < 1e-4, (f, err)
    print("ok")


if __name__ == "__main__":
    if sys.argv[1] == "check":
        check(sys.argv[2])
    else:
        main()
