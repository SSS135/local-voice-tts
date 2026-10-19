"""Host validation of the Silero v5_6_ru ONNX export: original apply_tts vs onnxruntime vs sherpa-onnx.

Usage: uv run python -I validate_host.py <v5_6_ru.pt> <export_dir> <wav_dir> [whisper_model]
Writes <wav_dir>/{orig,onnx,sherpa}_XX.wav (48 kHz PCM16) and host-validation.json next to this script.
Needs faster-whisper and scipy besides the export deps.
"""
import json
import os
import platform
import re
import sys
import time
from importlib.metadata import version
from pathlib import Path

import numpy as np
import onnxruntime as ort

if hasattr(os, "add_dll_directory"):
    # Windows: make sherpa-onnx bind the pip onnxruntime.dll, not an older System32 copy.
    os.add_dll_directory(str(Path(ort.__file__).parent / "capi"))
import sherpa_onnx  # noqa: E402
import soundfile as sf  # noqa: E402
import torch  # noqa: E402
from scipy.signal import resample_poly  # noqa: E402

sys.path.insert(0, str(Path(__file__).parent))
from export_onnx import load_package  # noqa: E402

SR = 48000
TYPES = ["st", "wh_q", "general_q", "alternative_q", "tag_q", "exclam"]
CASES = [  # (text, speaker); all single sentences so sherpa's sentence splitter sees exactly one
    ("В две тысячи двадцать четвёртом году мы посадили сорок три дерева.", "aidar"),
    ("В 2024 году мы посадили 43 дерева.", "baya"),
    ("Где ты оставил ключи от старого замка?", "kseniya"),
    ("Ты уже закрыл дверь на замок?", "eugene"),
    ("Тебе больше нравится чай или кофе?", "xenia"),
    ("Ты ведь придёшь завтра, правда?", "aidar"),
    ("Какой чудесный сегодня день!", "baya"),
    ("Все знают, что всё будет хорошо.", "kseniya"),
    ("Без муки не испечь хлеба, а долгое ожидание для него настоящая мука.", "eugene"),
    ("Белка прыгнула на ёлку, а в яйце много белка.", "xenia"),
    ("Вчера вечером, когда над рекой уже сгущались сумерки, мы с друзьями долго сидели у костра, "
     "пили горячий чай и рассказывали друг другу удивительные истории о далёких путешествиях.", "aidar"),
]


class Capture:
    """Stands in for part.models[0]; records the kwargs apply_tts passes to the scripted model."""

    def __init__(self, model):
        self.model, self.kwargs = model, None

    def __getattr__(self, name):
        return getattr(self.model, name)

    def __call__(self, **kw):
        self.kwargs = kw
        return self.model(**kw)


def norm(s):
    s = s.lower().replace("ё", "е").replace("+", "")
    return re.sub(r"\s+", " ", re.sub(r"[^\w\s]", " ", s)).strip()


def edit_distance(a, b):
    row = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        prev, row[0] = row[0], i
        for j, y in enumerate(b, 1):
            prev, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, prev + (x != y))
    return row[-1]


def wer_cer(ref, hyp):
    r, h = norm(ref), norm(hyp)
    return edit_distance(r.split(), h.split()) / max(1, len(r.split())), edit_distance(r, h) / max(1, len(r))


def mel_fb(n_fft=2048, n_mels=80):
    # HTK mel filterbank 0..SR/2
    mel = lambda f: 2595 * np.log10(1 + f / 700)  # noqa: E731
    pts = 700 * (10 ** (np.linspace(0, mel(SR / 2), n_mels + 2) / 2595) - 1)
    freqs = np.linspace(0, SR / 2, n_fft // 2 + 1)
    lo, ce, hi = pts[:-2, None], pts[1:-1, None], pts[2:, None]
    return np.maximum(0, np.minimum((freqs - lo) / (ce - lo), (hi - freqs) / (hi - ce)))


FB = mel_fb()


def log_mel(y, n_fft=2048, hop=512):
    y = np.pad(y, (0, max(0, n_fft - len(y))))
    frames = np.lib.stride_tricks.sliding_window_view(y, n_fft)[::hop] * np.hanning(n_fft)
    return np.log(FB @ (np.abs(np.fft.rfft(frames, axis=1)) ** 2).T + 1e-10)


def compare(ref, x):
    if ref.shape != x.shape:
        return {"same_length": False, "max_abs_diff": None, "snr_db": None, "logmel_mae": None, "mel_cosine": None}
    err = float(np.sum((ref - x) ** 2))
    a, b = log_mel(ref), log_mel(x)
    ea, eb = np.exp(a).ravel(), np.exp(b).ravel()
    return {"same_length": True, "max_abs_diff": float(np.abs(ref - x).max()),
            "snr_db": float(10 * np.log10(np.sum(ref ** 2) / err)) if err > 0 else float("inf"),
            "logmel_mae": float(np.abs(a - b).mean()),
            "mel_cosine": float(ea @ eb / (np.linalg.norm(ea) * np.linalg.norm(eb)))}


def cpu_name():
    try:
        import winreg
        k = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\CentralProcessor\0")
        return winreg.QueryValueEx(k, "ProcessorNameString")[0].strip()
    except OSError:
        return platform.processor()


def ort_session(path, threads):
    so = ort.SessionOptions()
    so.intra_op_num_threads, so.inter_op_num_threads = threads, 1
    return ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])


def ort_feeds(seq, sid, typ):
    return {"x": seq, "x_length": np.array([seq.shape[1]], np.int64),
            "noise_scale": np.array([0.667], np.float32), "length_scale": np.array([1.0], np.float32),
            "noise_scale_w": np.array([0.8], np.float32), "sid": np.array([sid], np.int64),
            "emotion_id": np.array([typ], np.int64)}


def timed(fn):
    t = time.perf_counter()
    out = fn()
    return out, time.perf_counter() - t


def main():
    pkg, exp, wav_dir = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    whisper_name = sys.argv[4] if len(sys.argv) > 4 else "small"
    wav_dir.mkdir(parents=True, exist_ok=True)
    torch.set_grad_enabled(False)
    torch.set_num_threads(2)
    torch.manual_seed(0)
    load = {}

    part, load["torch_package_s"] = timed(lambda: load_package(pkg))
    i2s, spk2id = part.id_to_symbol, part.speaker_to_ids[0]
    cap = Capture(part.models[0])
    part.models[0] = cap
    rows, orig = [], []
    for i, (text, spk) in enumerate(CASES):
        audio, dt = timed(lambda: part.apply_tts(text=text, speaker=spk, sample_rate=SR))
        kw = cap.kwargs
        seq = kw["sequence"].cpu().numpy()
        tids = kw["type_ids"][0].tolist()
        accented = "".join(i2s[t] for t in seq[0].tolist())
        assert accented[0] == "|" and accented[-1] == "~", accented
        trivial = (not kw["symb_durs"] and bool(torch.all(kw["durs_rate"] == 1)) and bool(torch.all(kw["pitch_coefs"] == 1))
                   and not bool(kw["focus_mask"].any()))
        rows.append({"idx": i, "text": text, "speaker": spk, "sid": int(kw["speaker_ids"][0]),
                     "prepared_text": part.prepare_text_input(text, None)[0], "model_text": accented[1:-1],
                     "type": TYPES[tids[0]], "type_id": tids[0], "type_ids_constant": len(set(tids)) == 1,
                     "plain_model_kwargs": trivial, "tokens": seq.shape[1],
                     "sherpa_single_sentence": not re.search(r"[.:?!]", accented[1:-2]),
                     "digits_dropped": bool(re.search(r"\d", text))})
        assert rows[-1]["sid"] == spk2id[spk]
        orig.append(audio.numpy().astype(np.float32))
        print(i, rows[-1]["type"], accented, f"{dt:.2f}s")
    part.models[0] = cap.model

    # onnxruntime parity (2 threads) on the exact captured sequence/speaker/type
    sess, load["ort_session_s"] = timed(lambda: ort_session(exp / "model.onnx", 2))
    seqs = [np.array([[part.symbol_to_id[c] for c in "|" + r["model_text"] + "~"]], np.int64) for r in rows]
    onnx = [sess.run(None, ort_feeds(s, r["sid"], r["type_id"]))[0][0] for s, r in zip(seqs, rows)]

    cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=str(exp / "model.onnx"), tokens=str(exp / "tokens.txt")),
        num_threads=2, provider="cpu"), max_num_sentences=1)
    tts, load["sherpa_init_s"] = timed(lambda: sherpa_onnx.OfflineTts(cfg))
    assert tts.sample_rate == SR, tts.sample_rate

    def sherpa_gen(r, cb=None):
        gc = sherpa_onnx.GenerationConfig()
        gc.sid, gc.speed, gc.silence_scale = r["sid"], 1.0, 1.0
        gc.extra = {"emotion_id": str(r["type_id"])}
        return np.array(tts.generate(r["model_text"], gc, cb).samples, np.float32)

    sherpa = [sherpa_gen(r) for r in rows]
    for i, r in enumerate(rows):
        r["samples"] = {"orig": len(orig[i]), "onnx": len(onnx[i]), "sherpa": len(sherpa[i])}
        r["duration_s"] = len(orig[i]) / SR
        r["onnx_vs_orig"] = compare(orig[i], onnx[i])
        r["sherpa_vs_onnx"] = compare(onnx[i], sherpa[i])
        r["sherpa_vs_orig"] = compare(orig[i], sherpa[i])
        for tag, y in (("orig", orig[i]), ("onnx", onnx[i]), ("sherpa", sherpa[i])):
            sf.write(wav_dir / f"{tag}_{i:02d}.wav", np.clip(y, -1, 1), SR, subtype="PCM_16")

    # RTF: warm-up on case 0, then time every case; RTF = sum(compute) / sum(audio duration)
    total_audio = sum(r["duration_s"] for r in rows)
    rtf = {"total_audio_s": total_audio}

    def run_rtf(fn):
        fn(0)
        per = [timed(lambda: fn(i))[1] for i in range(len(rows))]
        return {"rtf": sum(per) / total_audio, "total_compute_s": sum(per), "per_sentence_s": per}

    for th in (1, 2, 4):
        s = sess if th == 2 else ort_session(exp / "model.onnx", th)
        rtf[f"ort_{th}t"] = run_rtf(lambda i, s=s: s.run(None, ort_feeds(seqs[i], rows[i]["sid"], rows[i]["type_id"])))
    rtf["sherpa_2t"] = run_rtf(lambda i: sherpa_gen(rows[i]))
    rtf["torch_apply_tts_2t"] = run_rtf(lambda i: part.apply_tts(text=CASES[i][0], speaker=CASES[i][1], sample_rate=SR))

    first = []
    t0 = time.perf_counter()
    sherpa_gen(rows[0], lambda samples, progress: first.append(time.perf_counter() - t0) or 1)
    rtf["sherpa_ttfa_first_sentence_warm_s"] = first[0]
    rtf["sherpa_ttfa_audio_s"] = rows[0]["duration_s"]

    # ASR round trip
    from faster_whisper import WhisperModel
    asr, load["whisper_s"] = timed(lambda: WhisperModel(whisper_name, device="cpu", compute_type="int8", cpu_threads=8))
    means = {}
    for tag, ys in (("orig", orig), ("onnx", onnx), ("sherpa", sherpa)):
        ws, cs = [], []
        for r, y in zip(rows, ys):
            segs, _ = asr.transcribe(resample_poly(y, 1, 3).astype(np.float32), language="ru", beam_size=5,
                                     temperature=0.0, condition_on_previous_text=False, vad_filter=False)
            hyp = " ".join(s.text.strip() for s in segs)
            # digit sentences are scored against what the model actually spoke (digits removed by the package)
            ref = r["model_text"] if r["digits_dropped"] else r["text"]
            w, c = wer_cer(ref, hyp)
            r.setdefault("asr", {})[tag] = {"transcript": hyp, "wer": w, "cer": c}
            ws.append(w)
            cs.append(c)
        means[tag] = {"wer": float(np.mean(ws)), "cer": float(np.mean(cs))}

    result = {
        "host": {"cpu": cpu_name(), "platform": platform.platform(), "logical_cpus": os.cpu_count(),
                 "threads": {"torch": 2, "ort": [1, 2, 4], "sherpa": 2, "whisper": 8}},
        "versions": {p: version(p) for p in ("torch", "onnxruntime", "sherpa-onnx", "faster-whisper", "numpy")},
        "whisper_model": f"{whisper_name} (int8, cpu, language=ru, beam 5)",
        "load_times_s": load, "rtf": rtf, "asr_mean": means, "sentences": rows,
    }
    out = Path(__file__).parent / "host-validation.json"
    out.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")

    print(f"{'#':>2} {'spk':8} {'type':13} {'len':>7} {'|onnx-orig|':>11} {'SNR dB':>7} {'melMAE':>7} "
          f"{'|sh-onnx|':>9} {'WERo':>5} {'WERx':>5} {'WERs':>5}")
    for r in rows:
        c, s, a = r["onnx_vs_orig"], r["sherpa_vs_onnx"], r["asr"]
        f = lambda v, p: "n/a" if v is None else format(v, p)  # noqa: E731
        print(f"{r['idx']:>2} {r['speaker']:8} {r['type']:13} {r['samples']['orig']:>7} {f(c['max_abs_diff'], '.1e'):>11} "
              f"{f(c['snr_db'], '.1f'):>7} {f(c['logmel_mae'], '.4f'):>7} {f(s['max_abs_diff'], '.1e'):>9} "
              f"{a['orig']['wer']:.2f} {a['onnx']['wer']:.2f} {a['sherpa']['wer']:.2f}")
    print("ASR means", means)
    print("RTF", {k: round(v["rtf"], 4) for k, v in rtf.items() if isinstance(v, dict)},
          "TTFA", round(first[0], 3), "load", {k: round(v, 2) for k, v in load.items()})
    print("wrote", out)


if __name__ == "__main__":
    main()
