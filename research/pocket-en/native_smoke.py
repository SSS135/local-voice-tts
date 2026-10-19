"""Actual Sherpa1.13.8 CPU validation of the pinned hosted fixed-Alba English pack."""
import hashlib
import json
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parent
SHARED = ROOT.parent / "shtorm"
sys.path[:0] = [str(SHARED / "native-deps"), str(SHARED / "deps")]
import numpy as np
from scipy.io import wavfile
import sherpa_onnx as sherpa
import sentencepiece as spm

start = time.monotonic()
tts = sherpa.OfflineTts(sherpa.OfflineTtsConfig(model=sherpa.OfflineTtsModelConfig(
    pocket=sherpa.OfflineTtsPocketModelConfig(lm_flow=str(ROOT / "lm_flow.int8.onnx"), lm_main=str(ROOT / "lm_main.int8.onnx"),
        encoder=str(ROOT / "encoder.onnx"), decoder=str(ROOT / "decoder.int8.onnx"), text_conditioner=str(ROOT / "text_conditioner.onnx"),
        vocab_json=str(ROOT / "vocab.json"), token_scores_json=str(ROOT / "token_scores.json")),
    num_threads=2, debug=True, provider="cpu")))
load_seconds = time.monotonic() - start
config = sherpa.GenerationConfig()
config.reference_audio = np.zeros(1920, np.float32)
config.reference_sample_rate = 24000
config.num_steps = 4
config.silence_scale = 1.0
config.extra = {"seed": "123", "temperature": "0.7", "max_frames": "100", "frames_after_eos": "3",
                "chunk_size": "1", "min_char_in_sentence": "1", "max_char_in_sentence": "180"}
text = "Hello world."
tokenizer = spm.SentencePieceProcessor(model_file=str(ROOT / "tokenizer.model"))
print(json.dumps({"expectedTokenIds": tokenizer.encode(text, out_type=int)}), flush=True)
rows = []
for name, speed, prompt in (("clean", 1.0, np.zeros(1920, np.float32)),
                             ("other-dummy-prompt", 1.0, np.linspace(-0.1, 0.1, 2400, dtype=np.float32)),
                             ("speed-1.5", 1.5, np.zeros(1920, np.float32))):
    config.speed = speed
    config.reference_audio = prompt
    start = time.monotonic()
    audio = tts.generate(text, config)
    samples = np.asarray(audio.samples)
    assert samples.size and np.isfinite(samples).all()
    pcm = (np.clip(samples, -1, 1) * 32767).astype("<i2")
    wavfile.write(ROOT / ("native-" + name + ".wav"), audio.sample_rate, pcm)
    row = {"name": name, "speedArgument": speed, "seconds": time.monotonic() - start,
           "samples": len(samples), "sampleRate": audio.sample_rate,
           "rms": float(np.sqrt(np.mean(samples.astype(np.float64) ** 2))), "pcmSha256": hashlib.sha256(pcm.tobytes()).hexdigest()}
    rows.append(row)
    print(json.dumps(row), flush=True)
callback_count = 0
def stop_after_first(samples, progress):
    global callback_count
    callback_count += 1
    return 0
start = time.monotonic()
stopped = tts.generate(text, config, stop_after_first)
assert callback_count == 1 and len(stopped.samples) <= 1920, (callback_count, len(stopped.samples))
result = {"runtime": sherpa.__version__, "repoRevision": "ad5d3fae3fb2f10024583708597afca8337c094c",
          "loadSeconds": load_seconds, "runs": rows,
          "fixedVoiceIgnoresDummyPrompt": rows[0]["pcmSha256"] == rows[1]["pcmSha256"],
          "speedIgnored": rows[0]["pcmSha256"] == rows[2]["pcmSha256"],
          "callbackStop": {"calls": callback_count, "samples": len(stopped.samples), "seconds": time.monotonic() - start},
          "limitation": "Callback stop begins only after latent generation. Android immediate cancellation/replacement remains device integration work."}
assert result["fixedVoiceIgnoresDummyPrompt"] and result["speedIgnored"]
(ROOT / "native-result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
print(json.dumps(result), flush=True)
