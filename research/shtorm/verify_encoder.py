"""Compare converted real-prompt embeddings to unmodified pinned PocketTTS implementation."""
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent
sys.path[:0] = [str(ROOT / "deps")] + ([os.environ["LOCALVOICE_TORCH_PATH"]] if os.environ.get("LOCALVOICE_TORCH_PATH") else []) + [
               str(ROOT / "pocket-export-base" / "pocket-tts-41cbc84af539ea78a804ffca5f9c6edc1a22ce44")]
import numpy as np
import onnxruntime as ort
import torch
from scipy.io import wavfile
from pocket_tts import TTSModel

model = TTSModel.load_model(config=str(ROOT / "config_local.yaml"))
torch.set_num_threads(2)
options = ort.SessionOptions()
options.intra_op_num_threads = 2
options.inter_op_num_threads = 1
encoder = ort.InferenceSession(str(ROOT / "onnx" / "mimi_encoder.onnx"), options)
text = ort.InferenceSession(str(ROOT / "onnx" / "text_conditioner.onnx"), options)
rate, pcm = wavfile.read(ROOT / "onnx" / "voice-vctk-p228-24k.wav")
assert rate == 24000 and pcm.dtype == np.int16
audio = pcm.astype(np.float32).reshape(1, 1, -1) / 32768.0
rows = []
with torch.no_grad():
    for length in (1920, 24000, 50000, audio.shape[-1]):
        wave = audio[:, :, :length]
        expected = torch.cat((model.flow_lm.bos_before_voice, model._encode_audio(torch.from_numpy(wave))), dim=1).numpy()
        actual = encoder.run(None, {"audio": wave})[0]
        np.testing.assert_allclose(actual, expected, rtol=2e-4, atol=2e-4)
        rows.append({"samples": length, "embeddingShape": list(actual.shape),
                     "maxAbsoluteError": float(np.max(np.abs(actual - expected)))})
    for ids in ([730, 1205, 3911, 2228, 3914], list(range(32)), [3999, 1, 2]):
        inputs = np.array([ids], dtype=np.int64)
        expected = model.flow_lm.conditioner(torch.from_numpy(inputs)).numpy()
        actual = text.run(None, {"token_ids": inputs})[0]
        np.testing.assert_allclose(actual, expected, rtol=0, atol=0)
result = {"authenticModel": "ArtShtorm/Shtorm-PocketTTS-RU@33f6c328d62c4da5c1227bbe081e89b574a6183a",
          "unmodifiedReferenceSource": "kyutai-labs/pocket-tts@41cbc84af539ea78a804ffca5f9c6edc1a22ce44",
          "encoderBakedBosExactlyOnce": True, "encoderCases": rows,
          "textConditionerExactCases": 3, "note": "Original PyTorch encoder versus exported graph including learned BOS; real public prompt and varying lengths."}
(ROOT / "encoder-parity-result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
print(json.dumps(result), flush=True)
