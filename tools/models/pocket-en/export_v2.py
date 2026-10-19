"""Export Kyutai Pocket TTS english_2026-09_2 to the fixed-Alba Sherpa Pocket INT8 pack (same file layout as the soniqo v1 pack).

Usage (export venv from ../shtorm/requirements-export.txt): python -I export_v2.py WORK_DIR
Downloads pinned inputs into WORK_DIR/in, exports fp32 graphs with the pinned lookbe exporter, recovers the Alba
voice-prompt embedding from Kyutai's precomputed Alba KV state, quantizes, and writes WORK_DIR/pack plus
pack-manifest.json and export-result.json.
"""
import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
KYUTAI = "https://huggingface.co/kyutai/pocket-tts-without-voice-cloning/resolve/ab248779e0718743c013e20b2d812ace7c8f4bf7/languages/english_2026-09_2/"
INPUTS = [
    ("model.safetensors", KYUTAI + "model.safetensors", 219029196, "e106c451108e066119f070eb7cf6c9ff4a2ad93f3bf04c9914cc94c66b7a4eeb"),
    ("alba.safetensors", KYUTAI + "embeddings/alba.safetensors", 6244152, "4206b7194018c7e345e5cfd99f147caad36285df858ea153d0188c40d9f261e5"),
    ("tokenizer.model", KYUTAI + "tokenizer.model", 59339, "d461765ae179566678c93091c5fa6f2984c31bbe990bf1aa62d92c64d91bc3f6"),
    ("lookbe-source.zip", "https://codeload.github.com/lookbe/pocket-tts-onnx-export/zip/2b25e5a09d7b243edc4bbff7589363dc57a0204e", 152759, "e639306d009410685caa2727bfb7ca3eecaab4596b6cf645f7e2199c14443f73"),
    ("pocket-export-base.zip", "https://codeload.github.com/kyutai-labs/pocket-tts/zip/41cbc84af539ea78a804ffca5f9c6edc1a22ce44", 884387, "07959eab741ee181cd976b4e0b387fc23e887290ce95f2a7db2e43159f37a2b7"),
    ("pocket-tts-source.zip", "https://codeload.github.com/kyutai-labs/pocket-tts/zip/1df6b3da17cb43eff8c4c4e077f785520492adff", 893804, "b46447fbc363e1779e66cc5b990410299f4efcce3fa68fec080ed20d83dc99d1"),
]
EXPORTER = "pocket-tts-onnx-export-2b25e5a09d7b243edc4bbff7589363dc57a0204e"
BASE = "pocket-tts-41cbc84af539ea78a804ffca5f9c6edc1a22ce44"
LATEST = "pocket-tts-1df6b3da17cb43eff8c4c4e077f785520492adff"
SEQ_LEN = 1000


def sha(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        while chunk := stream.read(1 << 23):
            digest.update(chunk)
    return digest.hexdigest()


def fetch(work):
    (work / "in").mkdir(parents=True, exist_ok=True)
    for name, url, size, digest in INPUTS:
        target = work / "in" / name
        if not target.exists() and (HERE.parent / "shtorm" / name).exists():
            shutil.copyfile(HERE.parent / "shtorm" / name, target)
        if not target.exists():
            print("Downloading " + url, flush=True)
            urllib.request.urlretrieve(url, target)
        if target.stat().st_size != size or sha(target) != digest:
            raise SystemExit("Input checksum failed: " + name)
    for name in ("lookbe-source.zip", "pocket-export-base.zip", "pocket-tts-source.zip"):
        with zipfile.ZipFile(work / "in" / name) as archive:
            archive.extractall(work / "src")


def write_config(work):
    import yaml
    config = yaml.safe_load((work / "src" / LATEST / "pocket_tts" / "config" / "english_2026-09_2.yaml").read_text(encoding="utf-8"))
    config.pop("weights_path_without_voice_cloning")
    config["weights_path"] = (work / "in" / "model.safetensors").as_posix()
    # tokenizer.model is byte-identical to the one behind the upstream tokenizer.json; the graph export only uses the embedding table.
    config["flow_lm"]["lookup_table"].update(tokenizer="sentencepiece", tokenizer_path=(work / "in" / "tokenizer.model").as_posix())
    (work / "config_local.yaml").write_text(yaml.safe_dump(config), encoding="utf-8")


def export_stage(work, stage):
    """Run one pinned lookbe export script in-process with float32 recurrent states (Sherpa 1.13.8 rejects fp16 states)."""
    exporter = work / "src" / EXPORTER
    sys.path[:0] = [str(work / "src" / BASE), str(exporter)]
    os.environ["HF_HUB_OFFLINE"] = "1"
    script = exporter / "scripts" / ("export_mimi_and_conditioner.py" if stage == "mimi" else "export_flow_lm.py")
    sys.argv = [str(script), "--config", str(work / "config_local.yaml"), "--weights_path", str(work / "in" / "model.safetensors"),
                "--output_dir", str(work / "onnx"), "--seq_len", str(SEQ_LEN)] + (["--no_kv_delta"] if stage == "flow" else [])
    source = script.read_text(encoding="utf-8")
    assert source.count("USE_FLOAT16_STATES = True") == 1
    source = source.replace("USE_FLOAT16_STATES = True", "USE_FLOAT16_STATES = False")
    exec(compile(source, str(script), "exec"), {"__name__": "__main__", "__file__": str(script)})


def recover_alba(work):
    """Find the 127x1024 prompt (learned BOS + speaker_proj(latents)) whose KV cache equals Kyutai's precomputed Alba state.

    The open checkpoint has zeroed Mimi encoder weights, so the prompt cannot be re-encoded from audio. The KV cache is the
    only channel through which a voice prompt affects generation, so reproducing it reproduces Kyutai's Alba exactly.
    """
    sys.path.insert(0, str(work / "src" / BASE))
    import numpy as np
    import torch
    import torch.nn.functional as F
    from safetensors.torch import load_file
    from pocket_tts import TTSModel
    from pocket_tts.modules.stateful_module import init_states

    torch.manual_seed(0)
    flow = TTSModel.load_model(config=str(work / "config_local.yaml")).flow_lm.eval()
    for parameter in flow.parameters():
        parameter.requires_grad_(False)
    target = load_file(str(work / "in" / "alba.safetensors"))
    layers = len(flow.transformer.layers)
    caches = [target[f"transformer.layers.{i}.self_attn/cache"][:, 0] for i in range(layers)]  # [2, T, H, D]
    length = caches[0].shape[1]
    assert int(target["transformer.layers.0.self_attn/offset"]) == length and flow.insert_bos_before_voice

    def run(prompt):
        state = init_states(flow, batch_size=1, sequence_length=length)
        flow.transformer(prompt, state)
        return [state[f"transformer.layers.{i}.self_attn"]["cache"][:, 0] for i in range(layers)]

    torch.set_grad_enabled(False)
    first, second = flow.transformer.layers[0], flow.transformer.layers[1]
    double = lambda t: t.detach().double()
    # Rows after BOS are x = P z (P = speaker projection). Layer 0 values: v = Wv (g * (x - mean) / std + b), which is
    # linear in (z / std, -mean / std): a 33-unknown least-squares fit per row (Wv and g are too ill-conditioned to invert).
    projection = double(flow.speaker_proj_weight)
    gain, shift, wv = double(first.norm1.weight), double(first.norm1.bias), double(first.self_attn.in_proj.weight[2048:])
    basis = torch.cat([projection, torch.ones(1024, 1, dtype=torch.float64)], dim=1)
    fit = torch.linalg.lstsq(wv @ (gain[:, None] * basis), (double(caches[0][1].reshape(length, -1))[1:] - wv @ shift).T).solution.T
    directions = fit[:, :32] @ projection.T  # x / std
    h0 = torch.cat([F.layer_norm(double(flow.bos_before_voice[0]), (1024,), gain, shift), gain * (fit @ basis.T) + shift])
    # The layer 0 attention output depends only on the known h0, so layer 1 values fix each row's remaining scale.
    state = init_states(flow, batch_size=1, sequence_length=length)
    attention = double(first.self_attn(h0[None].float(), state)[0])
    target1 = double(caches[1][1].reshape(length, -1))[1:]

    # Explicit float64 FF block of layer 0 and value projection of layer 1, applied row-wise.
    def values_for(scale):
        y = scale[:, None] * directions + attention[1:]
        ff = F.gelu(F.layer_norm(y, (1024,), double(first.norm2.weight), double(first.norm2.bias)) @ double(first.linear1.weight).T, approximate="tanh")
        y = y + ff @ double(first.linear2.weight).T
        h1 = F.layer_norm(y, (1024,), double(second.norm1.weight), double(second.norm1.bias))
        return h1 @ double(second.self_attn.in_proj.weight[2048:]).T

    grid = torch.linspace(-7, 7, 281, dtype=torch.float64)  # log scale
    miss = lambda log_scale: (values_for(log_scale.exp()) - target1).norm(dim=1)
    best = grid[torch.stack([miss(torch.full((length - 1,), float(c), dtype=torch.float64)) for c in grid]).argmin(dim=0)]
    low, high = best - 0.05, best + 0.05
    for _ in range(80):  # Per-row ternary search.
        a, b = low + (high - low) / 3, high - (high - low) / 3
        closer = miss(a) < miss(b)
        high, low = torch.where(closer, b, high), torch.where(closer, low, a)
    rows = (((low + high) / 2).exp()[:, None] * directions).float()
    prompt = torch.cat([flow.bos_before_voice.detach(), rows[None]], dim=1)
    errors = [float((a - b).abs().max()) for a, b in zip(run(prompt), caches)]
    peak = max(float(c.abs().max()) for c in caches)
    print("Alba KV max abs error per layer " + repr(errors) + " (cache peak " + repr(peak) + ")", flush=True)
    assert max(errors) < 2e-3 * peak, errors
    np.save(work / "alba_prompt.npy", prompt.numpy().astype(np.float32))
    return {"positions": length, "kvMaxAbsErrorPerLayer": errors, "kvPeakAbs": peak}


def fixed_encoder(work, prompt_path, output):
    """Same structure as the soniqo v1 fixed-voice encoder: latents = constant + 0 * mean(audio)."""
    import numpy as np
    import onnx
    from onnx import TensorProto, helper, numpy_helper
    prompt = np.load(prompt_path)
    graph = helper.make_graph(
        [helper.make_node("ReduceMean", ["audio"], ["audio_mean"], keepdims=1),
         helper.make_node("Mul", ["audio_mean", "zero"], ["ignored_audio"]),
         helper.make_node("Add", ["audio_prompt", "ignored_audio"], ["latents"])],
        "fixed_alba_encoder",
        [helper.make_tensor_value_info("audio", TensorProto.FLOAT, [1, 1, "audio_len"])],
        [helper.make_tensor_value_info("latents", TensorProto.FLOAT, list(prompt.shape))],
        [numpy_helper.from_array(prompt, "audio_prompt"), numpy_helper.from_array(np.zeros((), np.float32), "zero")])
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)], producer_name="localvoice-pocket-en-v2")
    model.ir_version = 8
    onnx.checker.check_model(model)
    onnx.save(model, output)


def verify_main_graph(work, prompt_path):
    """Run the exported fp32 flow_lm_main on the fixed prompt and compare its KV states with Kyutai's Alba state."""
    import numpy as np
    import onnxruntime as ort
    from safetensors.numpy import load_file
    session = ort.InferenceSession(str(work / "onnx" / "flow_lm_main.onnx"), providers=["CPUExecutionProvider"])
    feeds = {"sequence": np.zeros((1, 0, 32), np.float32), "text_embeddings": np.load(prompt_path)}
    for item in session.get_inputs():
        if item.name.startswith("state_"):
            dtype = {"tensor(float)": np.float32, "tensor(int64)": np.int64, "tensor(bool)": np.bool_}[item.type]
            feeds[item.name] = np.zeros(item.shape, dtype)
    outputs = dict(zip([o.name for o in session.get_outputs()], session.run(None, feeds)))
    target = load_file(str(work / "in" / "alba.safetensors"))
    errors = []
    for layer in range(6):
        cache = target[f"transformer.layers.{layer}.self_attn/cache"][:, 0]
        length = cache.shape[1]
        k, v, offset = (outputs[f"out_state_{3 * layer + j}"] for j in range(3))
        assert int(offset[0]) == length, offset
        errors.append(float(max(np.abs(k[0, :length] - cache[0]).max(), np.abs(v[0, :length] - cache[1]).max())))
    print("ONNX main graph vs Kyutai Alba KV max abs error " + repr(errors), flush=True)
    assert max(errors) < 2e-2, errors
    return errors


def quantize(work, pack):
    sys.path.insert(0, str(work / "src" / EXPORTER / "scripts"))
    import quantize as lookbe
    for source, target in (("flow_lm_main", "lm_main.int8.onnx"), ("flow_lm_flow", "lm_flow.int8.onnx"), ("mimi_decoder", "decoder.int8.onnx")):
        lookbe.quantize_file(work / "onnx" / (source + ".onnx"), pack / target, source)


def tokenizer_tables(work, pack):
    import sentencepiece as spm
    sp = spm.SentencePieceProcessor(model_file=str(work / "in" / "tokenizer.model"))
    vocab = {sp.id_to_piece(i): i for i in range(sp.get_piece_size())}
    scores = {sp.id_to_piece(i): sp.get_score(i) for i in range(sp.get_piece_size())}
    # Byte-identical to the soniqo v1 tables (LF, trailing newline).
    (pack / "vocab.json").write_text(json.dumps(vocab, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    (pack / "token_scores.json").write_text(json.dumps(scores, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    shutil.copyfile(work / "in" / "tokenizer.model", pack / "tokenizer.model")


def main():
    work = Path(sys.argv[1]).resolve()
    if len(sys.argv) == 4 and sys.argv[2] == "--stage":
        return export_stage(work, sys.argv[3])
    fetch(work)
    write_config(work)
    for stage, graph in (("mimi", "mimi_decoder.onnx"), ("flow", "flow_lm_main.onnx")):
        if (work / "onnx" / graph).exists():
            continue  # Re-runs reuse finished stages; delete WORK_DIR/onnx to re-export.
        with open(work / ("export-" + stage + ".log"), "w", encoding="utf-8") as log:
            subprocess.run([sys.executable, "-I", __file__, str(work), "--stage", stage], check=True, stdout=log, stderr=subprocess.STDOUT)
    result = {"alba": recover_alba(work)}
    result["onnxMainKvMaxAbsError"] = verify_main_graph(work, work / "alba_prompt.npy")
    pack = work / "pack"
    pack.mkdir(exist_ok=True)
    fixed_encoder(work, work / "alba_prompt.npy", pack / "encoder.onnx")
    shutil.copyfile(work / "onnx" / "text_conditioner.onnx", pack / "text_conditioner.onnx")
    quantize(work, pack)
    tokenizer_tables(work, pack)
    for name in ("LICENSE", "README.md"):
        shutil.copyfile(HERE / name, pack / name)
    files = [{"path": p.name, "size": p.stat().st_size, "sha256": sha(p)} for p in sorted(pack.iterdir())]
    (work / "pack-manifest.json").write_text(json.dumps(files, indent=2), encoding="utf-8")
    (work / "export-result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({"files": files, **result}, indent=2), flush=True)


if __name__ == "__main__":
    main()
