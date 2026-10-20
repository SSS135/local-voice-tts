# NeuCodec decoder quality gate, docs/TTS-QUANT-QUALITY.md case (b). Windows host, onnxruntime 1.28.0 (closest PyPI
# release to the app's 1.28.2), ORT_ENABLE_ALL as in neutts_jni.cpp.
#   decode OUT forced.jsonl model.onnx...   one-shot decode of the forced sequences (quant_kld.py sample) plus the six
#                                           voices' reference codes -> OUT/<model stem>.npz
#   score OUT forced.jsonl REF ANCHOR CAND... per clip vs REF: SNR, pause residual, MR-STFT, log-mel L1, ViSQOL v3 audio
#                                           mode; per arm: UTMOS, WER (large-v3), SIM (ECAPA). Caches OUT/<arm>.*.json,
#                                           prints the gate for each CAND against ANCHOR.
# Arms are named by model stem. Downloads: set HF_HOME / TORCH_HOME to a dedicated directory.
# uv run --no-project --python 3.12 --with onnxruntime==1.28.0 --with numpy python -I tools/models/neutts/decoder_gate.py decode ...
# uv run --no-project --python 3.12 --with numpy --with scipy --with soundfile==0.14.0 --with librosa==1.0.0 --with auraloss==0.4.0
#   --with visqol-python==3.8.0 --with torch==2.11.0 --with torchaudio==2.11.0 --with speechbrain==1.1.2
#   --with faster-whisper==1.2.1 --with ctranslate2==4.8.3 --with openai-whisper==20250625 --with jiwer==4.0.0
#   python -I tools/models/neutts/decoder_gate.py score ...
import json, os, sys, zipfile
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
PACK = HERE / "build" / "pack"
SENTENCES = HERE.parents[2] / "tests" / "fixtures" / "tts-quality-en.txt"
VOICES = ["dave", "jo", "emily", "paul", "sophie", "steven"]
SR = 24000


def clips(forced):
    """(clip id, bootstrap group, voice, codes, text): forced sequences grouped by sentence, references by voice."""
    lines = SENTENCES.read_text(encoding="utf-8").splitlines()
    out = []
    for line in open(forced, encoding="utf-8"):
        s = json.loads(line)
        if len(s["codes"]) > 1:
            out.append((f"{s['voice']}-{s['sentence']}", s["sentence"], s["voice"], s["codes"], lines[s["sentence"]]))
    for i, v in enumerate(VOICES):
        codes = [int(c) for c in (PACK / f"voice-{v}.codes").read_text().split()]
        out.append((f"ref-{v}", 1000 + i, v, codes, (PACK / f"voice-{v}.txt").read_text(encoding="utf-8").strip()))
    return out


def decode(out, forced, models):
    import onnxruntime as ort
    os.makedirs(out, exist_ok=True)
    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    so.intra_op_num_threads = 4
    cs = clips(forced)
    for m in models:
        s = ort.InferenceSession(m, so, providers=["CPUExecutionProvider"])
        name = s.get_inputs()[0].name
        audio = {cid: s.run(None, {name: np.array(codes, np.int32).reshape(1, 1, -1)})[0].reshape(-1)
                 for cid, _, _, codes, _ in cs}
        np.savez(os.path.join(out, Path(m).stem + ".npz"), **audio)
        print(Path(m).stem, len(audio), flush=True)


def db(x):
    return 10 * np.log10(max(x, 1e-30))


def ref_metrics(ref, deg):
    """Distances of one arm's clips to the reference arm's (same codes, so time-aligned)."""
    import auraloss, librosa, torch
    from scipy.signal import resample_poly
    from visqol import VisqolApi
    mr = auraloss.freq.MultiResolutionSTFTLoss()
    vq = VisqolApi()
    vq.create(mode="audio")
    mel = lambda x: np.log(np.maximum(librosa.feature.melspectrogram(y=x, sr=SR, n_fft=1024, hop_length=256, n_mels=80), 1e-5))
    res = {}
    for cid in ref.files:
        r, d = ref[cid].astype(np.float64), deg[cid].astype(np.float64)
        e = d - r
        # pause frames: 20 ms frames where the reference is below -50 dBFS
        n = len(r) // 480 * 480
        fr, fe = r[:n].reshape(-1, 480), e[:n].reshape(-1, 480)
        quiet = 10 * np.log10(np.mean(fr ** 2, 1) + 1e-30) < -50
        res[cid] = {
            "snr": db(np.sum(r ** 2)) - db(np.sum(e ** 2)),
            "pause_sq": float(np.sum(fe[quiet] ** 2)), "pause_n": int(quiet.sum() * 480),
            "mrstft": float(mr(torch.tensor(d, dtype=torch.float32)[None, None], torch.tensor(r, dtype=torch.float32)[None, None])),
            "logmel_l1": float(np.mean(np.abs(mel(d.astype(np.float32)) - mel(r.astype(np.float32))))),
        }
        try:
            res[cid]["visqol"] = float(vq.measure_from_arrays(resample_poly(r, 2, 1), resample_poly(d, 2, 1), sample_rate=48000).moslqo)
        except ValueError:  # clip shorter than ViSQOL's minimum patch (about 0.5 s): excluded from ViSQOL statistics
            res[cid]["visqol"] = float("nan")
    return res


class Downstream:
    def __init__(self):
        import torch
        from faster_whisper import WhisperModel
        from speechbrain.inference.speaker import EncoderClassifier
        from whisper.normalizers import EnglishTextNormalizer
        self.torch = torch
        dev = "cuda" if os.environ.get("ASR_DEVICE") == "cuda" else "cpu"
        self.asr = WhisperModel("large-v3", device=dev, compute_type="float16" if dev == "cuda" else "int8")
        self.norm = EnglishTextNormalizer()
        self.mos = torch.hub.load("tarepan/SpeechMOS:v1.2.0", "utmos22_strong", trust_repo=True)
        self.spk = EncoderClassifier.from_hparams(source="speechbrain/spkrec-ecapa-voxceleb")
        import soundfile as sf
        z = zipfile.ZipFile(next(HERE.glob("build/neutts-*.zip")))
        self.prompt = {}
        for v in VOICES:
            name = next(n for n in z.namelist() if n.endswith(f"samples/{v}.wav"))
            x, sr = sf.read(z.open(name), dtype="float32")
            self.prompt[v] = self.embed(x if x.ndim == 1 else x.mean(1), sr)

    def embed(self, x, sr):
        import librosa
        e = self.spk.encode_batch(self.torch.tensor(librosa.resample(x, orig_sr=sr, target_sr=16000))[None]).reshape(-1)
        return (e / e.norm()).numpy()

    def run(self, arm, cs):
        import jiwer, librosa
        res = {}
        for cid, _, voice, _, text in cs:
            x = arm[cid].astype(np.float32)
            x16 = librosa.resample(x, orig_sr=SR, target_sr=16000)
            segs, _ = self.asr.transcribe(x16, beam_size=5, language="en")
            hyp = self.norm(" ".join(s.text for s in segs))
            ref = self.norm(text)
            w = jiwer.process_words(ref, hyp)
            with self.torch.no_grad():
                mos = float(self.mos(self.torch.tensor(x16)[None], 16000))
            res[cid] = {"err": w.substitutions + w.deletions + w.insertions, "words": len(ref.split()), "hyp": hyp,
                        "utmos": mos, "sim": float(self.embed(x, SR) @ self.prompt[voice])}
        return res


def cached(path, fn):
    if os.path.exists(path):
        return json.load(open(path))
    r = fn()
    json.dump(r, open(path, "w"), indent=0)
    return r


def boot(groups, fn, n=10000):
    """95% CI of fn(index array) over a paired bootstrap of whole groups (sentences)."""
    keys = sorted(set(groups))
    idx = {k: np.flatnonzero(np.array(groups) == k) for k in keys}
    rng = np.random.default_rng(0)
    vals = [fn(np.concatenate([idx[keys[j]] for j in rng.integers(0, len(keys), len(keys))])) for _ in range(n)]
    return [float(np.percentile(vals, 2.5)), float(np.percentile(vals, 97.5))]


def score(out, forced, ref, anchor, cands):
    cs = clips(forced)
    ids = [c[0] for c in cs]
    groups = [c[1] for c in cs]
    arms = {a: np.load(os.path.join(out, a + ".npz")) for a in [ref, anchor] + cands}
    rm = {a: cached(os.path.join(out, f"{a}.ref.json"), lambda a=a: ref_metrics(arms[ref], arms[a])) for a in [anchor] + cands}
    ds = None
    dm = {}
    for a in [anchor] + cands:
        p = os.path.join(out, f"{a}.down.json")
        if not os.path.exists(p) and ds is None:
            ds = Downstream()
        dm[a] = cached(p, lambda a=a: ds.run(arms[a], cs))
    col = lambda m, a, k: np.array([m[a][c][k] for c in ids], np.float64)
    report = {}
    b = anchor
    for a in [anchor] + cands:
        snr = col(rm, a, "snr")
        r = {"snr_median": float(np.median(snr)), "snr_p10": float(np.percentile(snr, 10)),
             "pause_residual_db": db(col(rm, a, "pause_sq").sum() / max(col(rm, a, "pause_n").sum(), 1)),
             "mrstft_mean": float(col(rm, a, "mrstft").mean()), "logmel_l1_mean": float(col(rm, a, "logmel_l1").mean()),
             "visqol_mean": float(np.nanmean(col(rm, a, "visqol"))),
             "visqol_skipped": int(np.isnan(col(rm, a, "visqol")).sum()),
             "utmos_mean": float(col(dm, a, "utmos").mean()), "sim_mean": float(col(dm, a, "sim").mean()),
             "wer": float(col(dm, a, "err").sum() / col(dm, a, "words").sum())}
        if a != b:
            words = col(dm, b, "words")
            dv, du, dsim = (col(rm, a, "visqol") - col(rm, b, "visqol"), col(dm, a, "utmos") - col(dm, b, "utmos"),
                            col(dm, a, "sim") - col(dm, b, "sim"))
            derr = col(dm, a, "err") - col(dm, b, "err")
            r["d_visqol_ci"] = boot(groups, lambda i: np.nanmean(dv[i]))
            r["d_utmos_ci"] = boot(groups, lambda i: du[i].mean())
            r["d_sim_ci"] = boot(groups, lambda i: dsim[i].mean())
            r["d_wer_ci"] = boot(groups, lambda i: derr[i].sum() / words[i].sum())
            rb = report[b]
            r["gate"] = {k: bool(v) for k, v in {
                "snr": r["snr_median"] >= rb["snr_median"] - 1 and r["snr_p10"] >= rb["snr_p10"] - 1,
                "pause": r["pause_residual_db"] <= rb["pause_residual_db"] + 1,
                "visqol": r["d_visqol_ci"][0] >= -0.05,
                "utmos": -0.05 <= r["d_utmos_ci"][0] and r["d_utmos_ci"][1] <= 0.05,
                "wer": r["d_wer_ci"][1] <= 0.01,
                "sim": r["d_sim_ci"][0] >= -0.01,
            }.items()}
        report[a] = r
    print(json.dumps(report, indent=1))
    json.dump(report, open(os.path.join(out, "gate.json"), "w"), indent=1)


if __name__ == "__main__":
    if sys.argv[1] == "decode":
        decode(sys.argv[2], sys.argv[3], sys.argv[4:])
    else:
        score(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5], sys.argv[6:])
