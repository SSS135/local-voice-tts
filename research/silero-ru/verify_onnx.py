"""Parity of the exported graph: scripted original vs onnxruntime vs sherpa-onnx VITS path.

Usage: uv run python verify_onnx.py <v5_6_ru.pt> <out_dir>
"""
import sys
from pathlib import Path

import os

import numpy as np
import onnxruntime as ort

if hasattr(os, "add_dll_directory"):
    # Windows: make sherpa-onnx bind the pip onnxruntime.dll, not an older System32 copy.
    os.add_dll_directory(str(Path(ort.__file__).parent / "capi"))
import sherpa_onnx  # noqa: E402
import torch  # noqa: E402

sys.path.insert(0, str(Path(__file__).parent))
from export_onnx import load_package, reference  # noqa: E402

CASES = [  # (accented model text, speaker id, sentence type id, length_scale)
    ("прив+ет, как дел+а?", 1, 2, 1.0),
    ("в н+едрах т+ундры в+ыдры в г+етрах т+ырят в в+ёдра +ядра к+едров.", 4, 0, 1.0),
    ("кто з+ав+ёт мен+я?", 0, 1, 1.0),
    ("м+ама мыла р+аму!", 3, 5, 0.7),
]


def main():
    pkg, out = Path(sys.argv[1]), Path(sys.argv[2])
    torch.set_grad_enabled(False)
    part = load_package(pkg)
    s2i = part.symbol_to_id
    sess = ort.InferenceSession(str(out / "model.onnx"), providers=["CPUExecutionProvider"])
    cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(out / "model.onnx"), tokens=str(out / "tokens.txt")),
        num_threads=2, provider="cpu"), max_num_sentences=1)
    tts = sherpa_onnx.OfflineTts(cfg)
    assert tts.sample_rate == 48000, tts.sample_rate
    worst = 0.0
    for text, sp, typ, scale in CASES:
        seq = torch.LongTensor([[s2i[c] for c in "|" + text + "~"]])
        ref = reference(part, seq, sp, typ, rate=1.0 / scale).numpy()
        feeds = {"x": seq.numpy(), "x_length": np.array([seq.shape[1]], np.int64),
                 "noise_scale": np.array([0.667], np.float32), "length_scale": np.array([scale], np.float32),
                 "noise_scale_w": np.array([0.8], np.float32), "sid": np.array([sp], np.int64),
                 "emotion_id": np.array([typ], np.int64)}
        onnx_y = sess.run(None, feeds)[0][0]
        gc = sherpa_onnx.GenerationConfig()
        gc.sid, gc.speed, gc.silence_scale = sp, 1.0 / scale, 1.0
        gc.extra = {"emotion_id": str(typ)}
        sh = np.array(tts.generate(text, gc).samples, np.float32)
        d1 = float(np.abs(onnx_y - ref).max()) if onnx_y.shape == ref.shape else float("inf")
        d2 = float(np.abs(sh - onnx_y).max()) if sh.shape == onnx_y.shape else float("inf")
        worst = max(worst, d1, d2)
        print(f"{text[:30]!r:34} ref={len(ref)} onnx={len(onnx_y)} sherpa={len(sh)} "
              f"|onnx-ref|={d1:.2e} |sherpa-onnx|={d2:.2e}")
    assert worst < 1e-3, worst
    print("OK worst", worst)


if __name__ == "__main__":
    main()
