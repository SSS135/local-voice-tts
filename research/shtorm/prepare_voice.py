"""Derive a bounded24kHz mono PCM16 prompt from the pinned CC-BY4 VCTK recording."""
import hashlib
import json
import math
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "deps"))
import numpy as np
from scipy.io import wavfile
from scipy.signal import resample_poly

rate, samples = wavfile.read(ROOT / "voice-vctk-p228.wav")
assert samples.dtype == np.int16
audio = samples.astype(np.float32) / 32768.0
if audio.ndim > 1:
    audio = audio.mean(axis=1)
if rate != 24000:
    divisor = math.gcd(rate, 24000)
    audio = resample_poly(audio, 24000 // divisor, rate // divisor).astype(np.float32)
audio = audio[:240000]
# Match PocketTTS end_on_pause:20ms RMS windows,35dB floor,20ms fade,80ms silence.
frame = 480
n = len(audio) // frame
rms = np.sqrt(np.mean(audio[:n * frame].reshape(n, frame) ** 2, axis=1))
db = 20 * np.log10(rms + 1e-12)
end = (np.flatnonzero(db > db.max() - 35)[-1] + 1) * frame
audio = audio[:end].copy()
fade = min(480, end)
audio[-fade:] *= np.linspace(1, 0, fade, dtype=np.float32)
audio = np.concatenate((audio, np.zeros(1920, np.float32)))
pcm = (np.clip(audio, -1, 1) * 32767).astype("<i2")
target = ROOT / "onnx" / "voice-vctk-p228-24k.wav"
wavfile.write(target, 24000, pcm)
report = {"source": "kyutai/tts-voices/vctk/p228_023_enhanced.wav",
          "sourceRevision": "323332d33f997de8394f24a193e1a76df720e01a",
          "sourceSha256": hashlib.sha256((ROOT / "voice-vctk-p228.wav").read_bytes()).hexdigest(),
          "license": "CC-BY-4.0", "output": target.name, "sampleRate": 24000,
          "samples": len(pcm), "bytes": target.stat().st_size,
          "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
          "changes": "Resampled mono24k; cropped to10s maximum; trimmed35dB trailing silence;20ms fade;80ms zero tail; PCM16 quantization."}
(ROOT / "voice-provenance.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
print(json.dumps(report), flush=True)
