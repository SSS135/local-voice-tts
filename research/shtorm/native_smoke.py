"""Real Sherpa1.13.8 CPU synthesis from locally converted authentic Shtorm graphs."""
import hashlib
import json
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parent
sys.path[:0] = [str(ROOT / "native-deps"), str(ROOT / "deps")]
import numpy as np
from scipy.io import wavfile
import sherpa_onnx as sherpa
import sentencepiece as spm

model = ROOT / "onnx"
start = time.monotonic()
config = sherpa.OfflineTtsConfig(model=sherpa.OfflineTtsModelConfig(
    pocket=sherpa.OfflineTtsPocketModelConfig(
        lm_flow=str(model / "flow_lm_flow.onnx"), lm_main=str(model / "flow_lm_main.onnx"),
        encoder=str(model / "mimi_encoder.onnx"), decoder=str(model / "mimi_decoder.onnx"),
        text_conditioner=str(model / "text_conditioner.onnx"), vocab_json=str(model / "bridge_vocab.json"),
        token_scores_json=str(model / "bridge_token_scores.json")), num_threads=2, debug=True, provider="cpu"))
tts = sherpa.OfflineTts(config)
load_seconds = time.monotonic() - start
rate, prompt = wavfile.read(model / "voice-vctk-p228-24k.wav")
assert rate == 24000 and prompt.dtype == np.int16 and prompt.ndim == 1
gen = sherpa.GenerationConfig()
gen.reference_audio = prompt.astype(np.float32) / 32768.0
gen.reference_sample_rate = 24000
gen.num_steps = 1
gen.silence_scale = 1.0
gen.extra = {"seed": "42", "temperature": "0.3", "max_frames": "100", "frames_after_eos": "3",
             "chunk_size": "15", "max_reference_audio_len": "12", "min_char_in_sentence": "1",
             "max_char_in_sentence": "4096"}
tokenizer = spm.SentencePieceProcessor(model_file=str(ROOT / "tokenizer.model"))
token_ids = tokenizer.encode("Приве́т, мир.", out_type=int)
assert 0 not in token_ids
bridge_text = "".join(chr(0xE000 + value) for value in token_ids)
print(json.dumps({"expectedTokenIds": token_ids}), flush=True)
rows = []
for speed in (1.0, 1.5):
    gen.speed = speed
    start = time.monotonic()
    audio = tts.generate(bridge_text, gen)
    samples = np.asarray(audio.samples)
    assert samples.size and np.isfinite(samples).all()
    pcm = (np.clip(samples, -1, 1) * 32767).astype("<i2")
    wavfile.write(ROOT / ("native-speed-" + str(speed) + ".wav"), audio.sample_rate, pcm)
    row = {"speedArgument": speed, "seconds": time.monotonic() - start, "samples": len(samples),
           "sampleRate": audio.sample_rate, "rms": float(np.sqrt(np.mean(samples.astype(np.float64) ** 2))),
           "pcmSha256": hashlib.sha256(pcm.tobytes()).hexdigest()}
    rows.append(row)
    print(json.dumps(row), flush=True)
report = {"runtime": sherpa.__version__, "loadSeconds": load_seconds, "runs": rows,
          "speedIgnored": rows[0]["pcmSha256"] == rows[1]["pcmSha256"]}
(ROOT / "native-result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
