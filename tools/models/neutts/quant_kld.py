# Teacher-forced backbone comparison for docs/TTS-QUANT-QUALITY.md (case a/c primary gate). Windows host, CPU.
#   sample: a GGUF samples code sequences per (sentence, voice, seed 1..N) with the production sampler: forced
#           sequences from the reference (N=1), or free-running outputs of each arm for the output-level stage.
#   score:  every GGUF re-evaluates those exact token sequences; at each code position the distribution over the
#           65,537 sampleable rows (speech codes + end token) is compared to the reference's.
# uv run --no-project --extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cpu --with llama-cpp-python==0.3.36 --with phonemizer==3.4.0 --with numpy python -I tools/models/neutts/quant_kld.py sample REF.gguf build/forced.jsonl [seeds]
# uv run --no-project --extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cpu --with llama-cpp-python==0.3.36 --with numpy python -I tools/models/neutts/quant_kld.py score REF.gguf build/forced.jsonl BASELINE.gguf CANDIDATE.gguf
import json, os, re, sys, zipfile
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
SENTENCES = HERE.parents[2] / "tests" / "fixtures" / "tts-quality-en.txt"
VOICES = ["dave", "jo"]  # en-GB 372 reference codes, en-US 653
TOP_K, TOP_P, MIN_P = 50, 0.95, 0.05  # NeuTtsNative.prefill sampler chain; temperature 1.0


def load(path, logits_all):
    from llama_cpp import Llama
    return Llama(model_path=str(path), n_ctx=2048, n_batch=512, n_ubatch=512, n_gpu_layers=0, n_threads=os.cpu_count() // 2,
                 logits_all=logits_all, verbose=False)


def special(llm, piece):
    ids = llm.tokenize(piece.encode(), add_bos=False, special=True)
    assert len(ids) == 1, piece
    return ids[0]


def sample(ref, out, seeds):
    wheel = HERE / "build" / "wheel"
    if not (wheel / "neutts" / "espeak-ng.dll").exists():
        zipfile.ZipFile(HERE / "build" / "neutts-1.4.1-cp312-cp312-win_amd64.whl").extractall(wheel)
    os.environ["ESPEAK_DATA_PATH"] = str(wheel / "neutts" / "espeak-ng-data")
    from phonemizer.backend.espeak.wrapper import EspeakWrapper
    EspeakWrapper.set_library(str(wheel / "neutts" / "espeak-ng.dll"))
    from phonemizer.backend import EspeakBackend
    g2p = EspeakBackend(language="en-us", preserve_punctuation=True, with_stress=True, words_mismatch="ignore",
                        language_switch="remove-flags")
    phones = lambda text: " ".join(g2p.phonemize([text])[0].split())  # NeuTTS._to_phones
    llm = load(ref, False)
    with open(out, "w", encoding="utf-8") as f:
        for voice in VOICES:
            ref_codes = (HERE / "build" / "pack" / f"voice-{voice}.codes").read_text().split()
            ref_text = (HERE / "build" / "pack" / f"voice-{voice}.txt").read_text(encoding="utf-8").strip()
            for i, text in enumerate(SENTENCES.read_text(encoding="utf-8").splitlines()):
                # NeuTTS._ggml_prompt, phoneme input
                prompt = (f"user: Convert the text to speech:<|TEXT_PROMPT_START|>{phones(ref_text)} {phones(text)}"
                          f"<|TEXT_PROMPT_END|>\nassistant:<|SPEECH_GENERATION_START|>" + "".join(f"<|speech_{c}|>" for c in ref_codes))
                for seed in range(1, seeds + 1):
                    llm.reset()  # samples over the full vocabulary; the app over speech rows (same unless text tokens reach the top-k)
                    r = llm(prompt, max_tokens=2048, temperature=1.0, top_k=TOP_K, top_p=TOP_P, min_p=MIN_P,
                            stop=["<|SPEECH_GENERATION_END|>"], seed=seed)["choices"][0]
                    codes = [int(c) for c in re.findall(r"<\|speech_(\d+)\|>", r["text"])]
                    f.write(json.dumps({"voice": voice, "sentence": i, "seed": seed, "ended": r["finish_reason"] == "stop",
                                        "prompt": prompt, "codes": codes}) + "\n")


def truncate(lp):
    """Production sampler support and probabilities: top-k, then top-p, then min-p (llama.cpp order and semantics)."""
    idx = np.argpartition(-lp, TOP_K)[:TOP_K]
    idx = idx[np.argsort(-lp[idx])]
    p = np.exp(lp[idx] - lp[idx[0]])
    p /= p.sum()
    keep = int(np.searchsorted(np.cumsum(p), TOP_P)) + 1  # smallest prefix with mass >= top_p
    idx, p = idx[:keep], p[:keep]
    mask = p >= MIN_P * p[0]
    idx, p = idx[mask], p[mask]
    return dict(zip(idx.tolist(), (p / p.sum()).tolist()))


def rows(llm, seq):
    """Log-probs over [end token, speech_0..speech_65535] at every position that predicts a code or the end token."""
    end, base = special(llm, "<|SPEECH_GENERATION_END|>"), special(llm, "<|speech_0|>")
    prompt = llm.tokenize(seq["prompt"].encode(), add_bos=True, special=True)
    forced = [base + c for c in seq["codes"]] + ([end] if seq["ended"] else [])
    llm.reset()
    llm.eval(prompt + forced)
    at = llm.scores[len(prompt) - 1: len(prompt) + len(forced) - 1]
    logits = np.concatenate([at[:, end:end + 1], at[:, base:base + 65536]], axis=1).astype(np.float64)
    lp = logits - logits.max(1, keepdims=True)
    lp -= np.log(np.exp(lp).sum(1, keepdims=True))
    target = np.array([0 if t == end else t - base + 1 for t in forced])
    return lp, target


def score(ref, forced, cands):
    seqs = [json.loads(line) for line in open(forced, encoding="utf-8")]
    models = [load(ref, True)] + [load(c, True) for c in cands]
    per_seq = {c: [] for c in cands}  # per sequence: (sum KL, top-1 agreements, sum TVD, sum dNLL, positions)
    kls = {c: [] for c in cands}
    for seq in seqs:
        lr, target = rows(models[0], seq)
        pr = np.exp(lr)
        tr = [truncate(x) for x in lr]
        for name, llm in zip(cands, models[1:]):
            lc, _ = rows(llm, seq)
            kl = (pr * (lr - lc)).sum(1)
            agree = (lr.argmax(1) == lc.argmax(1)).sum()
            tvd = [0.5 * sum(abs(a.get(k, 0.0) - b.get(k, 0.0)) for k in a.keys() | b.keys()) for a, b in zip(tr, map(truncate, lc))]
            dnll = (lr - lc)[np.arange(len(target)), target].sum()
            per_seq[name].append((kl.sum(), agree, sum(tvd), dnll, len(target)))
            kls[name].extend(kl.tolist())
    rng = np.random.default_rng(0)
    boot = rng.integers(0, len(seqs), (2000, len(seqs)))
    summary = {}
    for name in cands:
        s = np.array(per_seq[name])
        k = np.array(kls[name])
        n = s[:, 4].sum()
        summary[name] = {"positions": int(n), "mean_kld": s[:, 0].sum() / n, "median_kld": float(np.median(k)),
                         "p99_kld": float(np.percentile(k, 99)), "max_kld": float(k.max()), "same_top1": s[:, 1].sum() / n,
                         "mean_tvd_sampler": s[:, 2].sum() / n, "ln_ppl_ratio": s[:, 3].sum() / n}
        if name != cands[0]:  # paired over sequences against the first candidate (the shipped baseline)
            b = np.array(per_seq[cands[0]])
            ratio = s[boot, 0].sum(1) / b[boot, 0].sum(1)
            summary[name]["kld_ratio_vs_baseline_95ci"] = [float(np.percentile(ratio, 2.5)), float(np.percentile(ratio, 97.5))]
    print(json.dumps(summary, indent=1))


def check():
    lp = np.log(np.r_[0.6, 0.3, 0.06, 0.04, np.full(96, 1e-9)])
    q = truncate(lp)  # top-p 0.95 stops at 0.96 (3 tokens); min-p 0.03 keeps all three
    assert list(q) == [0, 1, 2] and abs(q[0] - 0.6 / 0.96) < 1e-6, q
    lp = np.log(np.r_[0.97, 0.02, 0.01, np.full(97, 1e-9)])
    assert list(truncate(lp)) == [0], truncate(lp)  # top-p keeps one token
    print("ok")


if __name__ == "__main__":
    if sys.argv[1] == "check":
        check()
    elif sys.argv[1] == "sample":
        sample(sys.argv[2], sys.argv[3], int(sys.argv[4]) if len(sys.argv) > 4 else 1)
    else:
        score(sys.argv[2], sys.argv[3], sys.argv[4:])
