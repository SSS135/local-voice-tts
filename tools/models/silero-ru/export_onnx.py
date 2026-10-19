"""Export Silero v5_6_ru (torch.package) to a sherpa-onnx VITS-compatible ONNX graph.

Usage: uv run python export_onnx.py <v5_6_ru.pt> <out_dir>

The graph re-implements the top-level MultiTTSModel.forward (no SSML/emphasis) with an eager
mirror of the scripted dur/pitch predictors, acoustic model and vocoder (weights copied):
  inputs  x[1,N] int64 ('|' text '~'), x_length[1], noise_scale[1], length_scale[1],
          noise_scale_w[1], sid[1] int64 (speaker), emotion_id[1] int64 (sentence type 0..5)
  output  y[1,L] float32 at 48 kHz
noise_scale/noise_scale_w/x_length are accepted for the sherpa VITS calling convention only.
"""
import json
import sys
from pathlib import Path

import numpy as np
import onnx
import torch
import torch.nn.functional as F

SAMPLE_RATE = 48000


def load_package(path):
    imp = torch.package.PackageImporter(str(path))
    model = imp.load_pickle("tts_models", "model")
    part = model.packages[0]
    assert len(part.models) == 1 and not part.phons
    return part


class SileroVits(torch.nn.Module):
    """Eager functional mirror of the scripted modules (weights copied, no scripted calls).

    torch.package-loaded RecursiveScriptModules can be neither traced into nor exported
    (their scripted MultiheadAttention fast-path branches defeat the TorchScript ONNX exporter).
    """

    def __init__(self, tm):
        super().__init__()
        self.names = {}
        for name, t in list(tm.named_parameters()) + list(tm.named_buffers()):
            key = name.replace(".", "__")
            self.register_buffer(key, t.detach().clone())
            self.names[name] = key
        tac = tm.tacotron
        assert tac.pitch_emb_type == "default", tac.pitch_emb_type
        self.pitch_strength = float(tac.pitch_strength)
        self.flags = {}
        for pre, mod in (("dur_predictor.dur_pred", tm.dur_predictor.dur_pred),
                         ("pitch_predictor.pitch_pred", tm.pitch_predictor.pitch_pred)):
            self.flags[pre] = (bool(mod.utt_type_emb), bool(mod.focus_emb))
        self.shorten = int(tac.decoder.shorten_factor)
        self.check_layers(tm)
        ist = tm.vocoder.head.istft
        assert ist.padding == "same" and ist.win_length == ist.n_fft and ist.n_fft % ist.hop_length == 0
        n, self.hop = ist.n_fft, ist.hop_length
        self.n_fft, self.overlap = n, n // ist.hop_length
        self.pad = (ist.win_length - ist.hop_length) // 2
        k = torch.arange(n // 2 + 1, dtype=torch.float64).unsqueeze(1)
        t = torch.arange(n, dtype=torch.float64).unsqueeze(0)
        weight = torch.full((n // 2 + 1, 1), 2.0, dtype=torch.float64)
        weight[0] = 1.0
        weight[-1] = 1.0
        ang = 2 * np.pi * k * t / n
        window = ist.window.to(torch.float64).unsqueeze(0)
        # irfft(n) as two real matmuls; imaginary DC/Nyquist terms are ignored like torch.fft.irfft.
        self.register_buffer("cos_basis", (weight * torch.cos(ang) / n * window).float())
        sin_b = weight * torch.sin(ang) / n * window
        sin_b[0] = 0
        sin_b[-1] = 0
        self.register_buffer("sin_basis", (-sin_b).float())
        self.register_buffer("window_sq", (ist.window.float() ** 2).reshape(self.overlap, self.hop))

    @staticmethod
    def check_layers(tm):
        # The functional mirror hard-codes these structural constants; fail loudly if upstream changes them.
        for name, m in tm.named_modules():
            kind = m.original_name
            if kind == "FFTBlock":
                assert not m.use_linear and not m.pitch_pred, name
            elif kind == "MultiheadAttention":
                assert m.batch_first and m._qkv_same_embed_dim and m.bias_k is None and not m.add_zero_attn, name
                assert m.num_heads == 2, name
            elif kind == "Conv1d":
                assert m.stride == (1,) and m.dilation == (1,) and m.padding_mode == "zeros", name
                assert m.padding == (m.kernel_size[0] // 2,), name
            elif kind == "GELU":
                assert m.approximate == "none", name
            elif kind == "AvgPool1d":
                assert m.kernel_size == m.stride and m.padding == (0,), name

    def P(self, name):
        return getattr(self, self.names[name])

    def linear(self, pre, x):
        return F.linear(x, self.P(pre + ".weight"), self.P(pre + ".bias"))

    def conv(self, pre, x, groups=1):
        w = self.P(pre + ".weight")
        return F.conv1d(x, w, self.P(pre + ".bias"), padding=w.shape[-1] // 2, groups=groups)

    def norm(self, pre, x, eps):
        w = self.P(pre + ".weight")
        return F.layer_norm(x, w.shape, w, self.P(pre + ".bias"), eps)

    def mha(self, pre, x, heads=2):
        # torch MultiheadAttention, batch_first, no mask (the original all-False pad mask is a no-op).
        B, L, C = x.shape
        q, k, v = F.linear(x, self.P(pre + ".in_proj_weight"), self.P(pre + ".in_proj_bias")).chunk(3, -1)
        d = C // heads
        q = q.reshape(B, L, heads, d).transpose(1, 2)
        k = k.reshape(B, L, heads, d).transpose(1, 2)
        v = v.reshape(B, L, heads, d).transpose(1, 2)
        att = torch.softmax(q @ k.transpose(-1, -2) / float(d) ** 0.5, -1)
        out = (att @ v).transpose(1, 2).reshape(B, L, C)
        return self.linear(pre + ".out_proj", out)

    def fft_block(self, pre, x):
        x = self.norm(pre + ".norm1", x + self.mha(pre + ".self_attn", x), 1e-5)
        h = x.transpose(1, 2)
        h = h + self.conv(pre + ".conv2", torch.relu(self.conv(pre + ".conv1", h)))
        return self.norm(pre + ".norm2", h.transpose(1, 2), 1e-5)

    def pos(self, pre, x):
        pe = self.P(pre + ".pe")[: x.shape[1], 0, :]
        return x + self.P(pre + ".scale") * pe.unsqueeze(0)

    def forward_transformer(self, pre, x, layers):
        x = self.pos(pre + ".pos_encoder", x)
        for i in range(layers):
            x = self.fft_block(f"{pre}.layers.{i}", x)
        return self.norm(pre + ".norm", x, 1e-5)

    def hourglass(self, pre, x):
        L = x.shape[1]
        m = self.shorten
        x = self.pos(pre + ".pos_encoder", x)
        x = self.fft_block(pre + ".pre_vanilla_layers.0", x)
        lp = (L + m - 1) // m * m
        x = F.pad(x, [0, 0, 0, m - 1])[:, :lp]
        down = F.avg_pool1d(x.transpose(1, 2), m, m).transpose(1, 2)
        # Upstream computes shorten_layers.0 on `down` and discards it; only layer 1 feeds the output.
        h = self.fft_block(pre + ".shorten_layers.1", down)
        up = self.linear(pre + ".upsample.proj", h).reshape(x.shape[0], -1, x.shape[2])
        x = (up + x)[:, :L]
        x = self.fft_block(pre + ".post_vanilla_layers.0", x)
        return self.norm(pre + ".norm", x, 1e-5)

    def series_predictor(self, pre, seq, sid, type_ids, focus):
        x = F.embedding(seq, self.P(pre + ".embedding.weight"))
        x = x + F.embedding(sid, self.P(pre + ".speaker_embedding.weight")).unsqueeze(1)
        use_type, use_focus = self.flags[pre]
        if use_type:
            x = x + F.embedding(type_ids, self.P(pre + ".type_embedding.weight"))
        if use_focus:
            x = x + F.embedding(focus, self.P(pre + ".focus_embedding.weight"))
        x = self.forward_transformer(pre + ".transformer", x, 4)
        return self.linear(pre + ".lin", x)

    def overlap_add(self, frames):
        # frames [T, overlap, hop] -> [(T + overlap - 1) * hop]
        out = F.pad(frames[:, 0, :], [0, 0, 0, self.overlap - 1])
        for j in range(1, self.overlap):
            out = out + F.pad(frames[:, j, :], [0, 0, j, self.overlap - 1 - j])
        return out.reshape(-1)

    @staticmethod
    def length_regulate(enc, dur):
        # enc [1,N,C], dur [1,N] rounded >= 0 -> [1,sum(dur),C] (repeat_interleave without a loop)
        cum = torch.cumsum(dur[0].long(), 0)
        frames = torch.arange(cum[-1], dtype=torch.long)
        idx = (frames.unsqueeze(1) >= cum.unsqueeze(0)).long().sum(1)
        return enc[:, idx, :]

    def forward(self, x, x_length, noise_scale, length_scale, noise_scale_w, sid, emotion_id):
        seq = x
        type_ids = emotion_id.view(1, 1).expand_as(seq)
        focus = torch.zeros_like(seq)
        log_dur = self.series_predictor("dur_predictor.dur_pred", seq, sid, type_ids, focus).squeeze(-1)
        dur = torch.round(torch.clamp(torch.exp(log_dur) - 1, min=0.0))
        dur = torch.cat([torch.clamp(dur[:, :1], max=5.0), dur[:, 1:]], 1)
        dur = torch.round(dur * length_scale)
        # MultiTTSModel.fx_durs (non-emphasis): sos <= 5, last <= 7, second-last = 13, third-last <= 13.
        dur = torch.cat([
            torch.clamp(dur[:, :1], max=5.0),
            dur[:, 1:-3],
            torch.clamp(dur[:, -3:-2], max=13.0),
            torch.full_like(dur[:, -2:-1], 13.0),
            torch.clamp(dur[:, -1:], max=7.0),
        ], 1)
        pitch = self.series_predictor("pitch_predictor.pitch_pred", seq, sid, type_ids, focus).transpose(1, 2)
        pitch = torch.where(pitch.abs() < 0.001, torch.zeros_like(pitch), pitch)

        pre = "tacotron"
        emb = F.embedding(seq, self.P(pre + ".embedding.weight"))
        enc = self.forward_transformer(pre + ".encoder", emb, 4)
        enc = enc + F.embedding(sid, self.P(pre + ".speaker_embedding.weight")).unsqueeze(1)
        enc = enc + self.conv(pre + ".pitch_proj", pitch).transpose(1, 2) * self.pitch_strength
        dec = self.hourglass(pre + ".decoder", self.length_regulate(enc, dur))
        mel = self.linear(pre + ".lin", dec).transpose(1, 2)

        pre = "vocoder.backbone"
        h = self.conv(pre + ".embed", mel)
        h = self.norm(pre + ".norm", h.transpose(1, 2), 1e-6).transpose(1, 2)
        for i in range(8):
            b = f"{pre}.convnext.{i}"
            y = self.conv(b + ".dwconv", h, groups=h.shape[1]).transpose(1, 2)
            y = self.linear(b + ".pwconv2", F.gelu(self.linear(b + ".pwconv1", self.norm(b + ".norm", y, 1e-6))))
            h = h + (self.P(b + ".gamma") * y).transpose(1, 2)
        feats = self.norm(pre + ".final_layer_norm", h.transpose(1, 2), 1e-6)
        spec = self.linear("vocoder.head.out", feats).transpose(1, 2)
        mag, phase = torch.chunk(spec, 2, 1)
        mag = torch.clamp(torch.exp(mag), max=100.0)
        re = (mag * torch.cos(phase))[0].transpose(0, 1)  # [T, bins]
        im = (mag * torch.sin(phase))[0].transpose(0, 1)
        frames = re @ self.cos_basis + im @ self.sin_basis  # windowed irfft frames [T, n_fft]
        T = frames.shape[0]
        audio = self.overlap_add(frames.reshape(T, self.overlap, self.hop))
        env = self.overlap_add(self.window_sq.unsqueeze(0).expand(T, -1, -1))
        audio = (audio / env)[self.pad:-self.pad]
        unused = (noise_scale + noise_scale_w + x_length.float()).sum() * 0.0
        return (audio + unused).unsqueeze(0)


def reference(part, seq, speaker, type_id, rate=1.0):
    """The original scripted MultiTTSModel on the same token sequence (no SSML/emphasis)."""
    tm = part.models[0]
    n = seq.shape[1]
    out, _ = tm(sequence=seq, speaker_ids=torch.LongTensor([speaker]), sr=SAMPLE_RATE, symb_durs={},
                durs_rate=torch.full((1, n), float(rate)), pitch_coefs=torch.ones(1, n),
                device="cpu", type_ids=torch.full((1, n), type_id, dtype=torch.long),
                focus_mask=torch.zeros(1, n, dtype=torch.long))
    return out[0]


def main():
    pkg, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)
    torch.set_grad_enabled(False)
    part = load_package(pkg)
    tm = part.models[0].eval()
    wrapper = SileroVits(tm).eval()

    s2i = part.symbol_to_id
    for text, speaker, type_id in (("|прив+ет, как дел+а?~", 1, 2), ("|в н+едрах т+ундры в+ыдры.~", 4, 0)):
        seq = torch.LongTensor([[s2i[c] for c in text]])
        args = (seq, torch.LongTensor([seq.shape[1]]), torch.FloatTensor([0.667]), torch.FloatTensor([1.0]),
                torch.FloatTensor([0.8]), torch.LongTensor([speaker]), torch.LongTensor([type_id]))
        ours, ref = wrapper(*args)[0], reference(part, seq, speaker, type_id)
        assert ours.shape == ref.shape, (ours.shape, ref.shape)
        print("eager mirror vs scripted reference: samples", len(ours), "max abs diff", float((ours - ref).abs().max()))

    onnx_path = out_dir / "model.onnx"
    torch.onnx.export(
        wrapper, args, str(onnx_path), dynamo=False, opset_version=17,
        input_names=["x", "x_length", "noise_scale", "length_scale", "noise_scale_w", "sid", "emotion_id"],
        output_names=["y"], dynamic_axes={"x": {1: "N"}, "y": {1: "L"}})
    model = onnx.load(str(onnx_path))
    meta = {
        "model_type": "vits", "comment": "silero v5_6_ru (local-voice export)", "language": "Russian",
        "sample_rate": str(SAMPLE_RATE), "n_speakers": str(len(part.speaker_to_ids[0])), "num_emotions": "6",
        "add_blank": "0", "frontend": "characters", "use_eos_bos": "1",
        "bos_id": str(s2i[part.sos_token]), "eos_id": str(s2i[part.eos_token]), "pad_id": str(s2i["_"]),
        "speakers": json.dumps(part.speaker_to_ids[0], ensure_ascii=False),
        "license": "CC BY-NC-SA 4.0 (Silero, snakers4/silero-models)",
    }
    for k, v in meta.items():
        p = model.metadata_props.add()
        p.key, p.value = k, v
    onnx.save(model, str(onnx_path))

    # sherpa character frontend: "<char> <id>" per line; space is a line with only its id.
    with open(out_dir / "tokens.txt", "w", encoding="utf-8", newline="\n") as f:
        for sym, idx in sorted(s2i.items(), key=lambda kv: kv[1]):
            f.write(f"{idx}\n" if sym == " " else f"{sym} {idx}\n")
        # Private-use aliases: the app sends sentence punctuation as these so sherpa's character
        # frontend never splits an utterance; the app appends '~' (eos) itself.
        for i, sym in enumerate(".:?!"):
            f.write(f"{chr(0xE000 + i)} {s2i[sym]}\n")
    print("wrote", onnx_path, "and tokens.txt")


if __name__ == "__main__":
    main()
