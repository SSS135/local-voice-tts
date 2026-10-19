"""Convert authentic Shtorm with pinned export code; no downloads or base-model substitution."""
import argparse
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent
EXPORTER = ROOT / "lookbe-src" / "pocket-tts-onnx-export-2b25e5a09d7b243edc4bbff7589363dc57a0204e"
BASE = ROOT / "pocket-export-base" / "pocket-tts-41cbc84af539ea78a804ffca5f9c6edc1a22ce44"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("stage", choices=["mimi", "flow"])
parser.add_argument("--torch-path", type=Path, default=None, help="Optional existing CPU torch package directory; normally use the active venv")
args = parser.parse_args()
sys.path[:0] = [str(ROOT / "deps")] + ([str(args.torch_path)] if args.torch_path else []) + [str(BASE), str(EXPORTER)]
os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["OMP_NUM_THREADS"] = "2"
os.environ["MKL_NUM_THREADS"] = "2"
print("Importing CPU export dependencies", flush=True)
import torch
from pocket_tts import TTSModel
import onnx_export.wrappers as wrappers

torch.set_num_threads(2)
original_load = TTSModel.load_model
loaded_model = None


def load_local(*values, **keywords):
    global loaded_model
    loaded_model = original_load(*values, **keywords)
    print("Loaded authentic local model; parameters=" + str(sum(p.numel() for p in loaded_model.parameters())), flush=True)
    return loaded_model


TTSModel.load_model = staticmethod(load_local)
original_encoder = wrappers.MimiEncoderWrapper


class SherpaVoiceEncoder(original_encoder):
    def __init__(self, *values, **keywords):
        super().__init__(*values, **keywords)
        if loaded_model is None or not loaded_model.config.flow_lm.insert_bos_before_voice:
            raise AssertionError("Shtorm requires exactly one BOS-before-voice prefix")
        self.register_buffer("shtorm_voice_bos", loaded_model.flow_lm.bos_before_voice.detach().clone().reshape(1, 1, -1))

    def forward(self, audio):
        voice = super().forward(audio)
        # Sherpa feeds this encoder output once before text; bake Shtorm's missing learned prefix here.
        return torch.cat((self.shtorm_voice_bos.expand(voice.shape[0], -1, -1), voice), dim=1)


wrappers.MimiEncoderWrapper = SherpaVoiceEncoder
script = EXPORTER / "scripts" / ("export_mimi_and_conditioner.py" if args.stage == "mimi" else "export_flow_lm.py")
sys.argv = [str(script), "--config", str(ROOT / "config_local.yaml"),
            "--weights_path", str(ROOT / "model_fast.safetensors"),
            "--output_dir", str(ROOT / "onnx"), "--seq_len", "1000"]
if args.stage == "flow":
    sys.argv.append("--no_kv_delta")
source = script.read_text(encoding="utf-8")
assert source.count("USE_FLOAT16_STATES = True") == 1
# Sherpa1.13.8 initializes state inputs only for float32/int64/bool; FP16 states are rejected.
source = source.replace("USE_FLOAT16_STATES = True", "USE_FLOAT16_STATES = False")
exec(compile(source, str(script), "exec"), {"__name__": "__main__", "__file__": str(script)})
