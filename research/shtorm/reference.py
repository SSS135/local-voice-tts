"""Authentic Shtorm CPU reference; all model, tokenizer and prompt inputs are local."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--source", type=Path, default=ROOT / "pocket-src" / "pocket-tts-1df6b3da17cb43eff8c4c4e077f785520492adff")
parser.add_argument("--torch-path", type=Path, default=None)
parser.add_argument("--load-only", action="store_true")
args = parser.parse_args()
sys.path[:0] = [str(ROOT / "deps")] + ([str(args.torch_path)] if args.torch_path else []) + [str(args.source)]
os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["OMP_NUM_THREADS"] = "2"
os.environ["MKL_NUM_THREADS"] = "2"

import numpy as np
import scipy.io.wavfile
import torch
import yaml
from pocket_tts import TTSModel

config = yaml.safe_load((ROOT / "config_fast.yaml").read_text(encoding="utf-8"))
config["weights_path"] = (ROOT / "model_fast.safetensors").as_posix()
config["flow_lm"]["lookup_table"]["tokenizer_path"] = (ROOT / "tokenizer.model").as_posix()
local_config = ROOT / "config_local.yaml"
local_config.write_text(yaml.safe_dump(config), encoding="utf-8")

torch.manual_seed(42)
start = time.monotonic()
model = TTSModel.load_model(config=str(local_config))
torch.set_num_threads(2)
report = {"modelPin": "33f6c328d62c4da5c1227bbe081e89b574a6183a", "torch": torch.__version__,
          "loadSeconds": time.monotonic() - start, "sampleRate": model.sample_rate,
          "parameters": sum(p.numel() for p in model.parameters()),
          "flowLayers": len(model.flow_lm.transformer.layers)}
print(json.dumps(report), flush=True)
if not args.load_only:
    start = time.monotonic()
    voice = model.get_state_for_audio_prompt(str(ROOT / "voice-vctk-p228.wav"))
    report["promptSeconds"] = time.monotonic() - start
    start = time.monotonic()
    audio = model.generate_audio(voice, "Приве́т, мир.")
    samples = audio.detach().cpu().numpy().reshape(-1)
    if samples.size == 0 or not np.isfinite(samples).all():
        raise AssertionError("reference output is empty or nonfinite")
    pcm = (np.clip(samples, -1, 1) * 32767).astype("<i2")
    scipy.io.wavfile.write(ROOT / "reference.wav", model.sample_rate, pcm)
    report.update(generationSeconds=time.monotonic() - start, samples=len(samples),
                  audioSeconds=len(samples) / model.sample_rate,
                  rms=float(np.sqrt(np.mean(samples.astype(np.float64) ** 2))),
                  pcmSha256=hashlib.sha256(pcm.tobytes()).hexdigest())
(ROOT / "reference-result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
print(json.dumps(report), flush=True)
