# TTS quantization quality: measurement protocol

Researched 2026-10-20. Protocol for judging quantization, export and engine changes to NeuTTS Nano (`docs/NEUTTS-OPTIMIZATION.md` items 1, 6 and 7, and the MNN row). It applies to any backend that uses an LM plus a codec. Nothing here has been run yet.
- Tooling: `tools/models/neutts/quant_kld.py` (forced-sequence sampling and KL scoring).
- Text set: `tests/fixtures/tts-quality-en.txt`.

Link prefixes:
- `LC` = https://github.com/ggml-org/llama.cpp/blob/412a363c655b479279d3050d0d1c67a5733697b4 (b11729, our pin)
- `AX` = https://arxiv.org/abs/

"Policy" marks a threshold we chose. Every other threshold cites its source.

## Verdict on the current plan ("WER check" + "SNR vs fp32 decode")

**The plan is incomplete, and for the backbone it is wrong.**

- **SNR is meaningless for the backbone.** At temperature 1 / top-k 50, two models emit different code sequences from the first step where their samples differ. Their waveforms are then unrelated, so SNR measures nothing.
- **SNR is valid for the decoder, but only as a regression detector.** The same codes give time-aligned output.
  - SNR-type scores track perceived quality poorly. In EnCodec, the discriminator with the best SI-SNR (7.35) got the worst MUSHRA (60.7) ([AX 2210.13438](https://arxiv.org/abs/2210.13438), Table 2).
  - Moshi reports "a concerning lack of correlation between objective and subjective audio quality metrics" ([AX 2410.00037](https://arxiv.org/abs/2410.00037)).
- **One sample per sentence scored with faster-whisper `small` (the step-2 acceptance in `NEUTTS-OPTIMIZATION.md`) is underpowered and underspecified.**
  - It has no seeds, no text normalization, no paired statistics and no margin.
  - A small ASR model adds its own errors, and those errors swamp a small TTS delta.
  - Seed-TTS-eval and F5-TTS use Whisper large-v3 ([seed-tts-eval README](https://github.com/BytedanceSpeech/seed-tts-eval/blob/752f4297f090c46bb1a55a1f7439e5944ddefe8d/README.md); [F5-TTS utils_eval.py](https://github.com/SWivid/F5-TTS/blob/2f866649b779881af8bd644e76b9a6d95053da29/src/f5_tts/eval/utils_eval.py)).
- **What the plan misses:**
  1. **A deterministic distribution metric.** Use teacher-forced KL divergence and top-1 agreement on fixed code sequences. They need no seeds and have low variance. KL and "flips" correlate (Spearman 0.981), and both catch changes that accuracy and perplexity average away ([AX 2407.09141](https://arxiv.org/abs/2407.09141)).
  2. **Failure counting.** This is the dominant failure of a quantized codec LM: no end token, runaway length, skipped or repeated words.
     - Moshi needed a separate token-entropy artifact detector because MOSNet missed "increased repetitiveness". Artifact-free samples were 95.8% at fp, 95.7% at W4 and 80.7% at W3 ([AX 2410.00037](https://arxiv.org/abs/2410.00037) §5.8, App. D).
     - ELLA-V counts runs that go past 2× the expected length ([AX 2401.07333](https://arxiv.org/abs/2401.07333)).
  3. **Speaker similarity (SIM)**, which catches voice-clone drift.
  4. **A naturalness predictor (UTMOS)**, which catches artifacts that WER does not.
  5. **A statistical design.** Use N sentences × M seeds, a paired bootstrap, non-inferiority margins, and an A/A run that measures the noise floor.
  6. **The quantize pitfalls listed under case (a).** `--pure` also requantizes the tied 194k-row head, an imatrix confound, and llama.cpp's own guard on early `ffn_down` layers.

## Principles

- **Measure against the shipped artifact (non-inferiority).**
  - The shipped Q4_0 GGUF and int8 decoder are upstream's published choices. They set the "acceptable" level.
  - Q8_0 / fp32 are the references that distances are measured from.
- **Order: deterministic first, then sampled.**
  - Deterministic first: KL for the LM, and reference-based audio distances for the decoder.
  - Sampled second: output-level metrics over sentences × seeds.
- **Statistics.**
  - Use a paired bootstrap over sentences with 10,000 resamples and a 95% CI. Resample whole sentences, keeping all voices and seeds of a sentence together.
    - Bisani & Ney: the paired difference has "much lower variance" ([ICASSP 2004](http://www-i6.informatik.rwth-aachen.de/PostScript/InterneArbeiten/Bisani_BootstrapEstimatesForConfidenceIntervalsInASRPerformanceEvaluation_ICASSP_2004.pdf)). They recommend block resampling.
    - Koehn: paired bootstrap resampling over sentences ([W04-3250](https://aclanthology.org/W04-3250/)).
  - A change is accepted only when every gate passes.
- **TTS precedent.** Park et al. quantized Kokoro, Supertonic and Orpheus with this setup ([AX 2609.28974](https://arxiv.org/abs/2609.28974), code [uxfacdev/tts-ptq-map](https://github.com/uxfacdev/tts-ptq-map)):
  - 200 fixed sentences, fixed seeds, and paired 95% bootstrap intervals.
  - "quality-preserving" means the ΔUTMOS interval lies within **[−0.05, 0.05]** and the upper end of the ΔWER interval is **≤ 0.01**.
  - The across-seed SD of the paired ΔUTMOS was below 0.06.
  - We adopt these margins.

## Test text

`tests/fixtures/tts-quality-en.txt`: 100 original sentences, one per line.

| Lines | Content |
|---|---|
| 1–40 | Plain |
| 41–55 | Numbers, dates, currency, units |
| 56–70 | Hard words, names, homographs, tongue twisters |
| 71–80 | Abbreviations and symbols |
| 81–90 | Short utterances and questions |
| 91–100 | Long sentences, ≤ 218 chars, which fit one NeuTTS prompt |

- **Voices:** dave (en-GB, 372 reference codes) and jo (en-US, 653). That gives 200 cells (sentence × voice).
- **Why not reuse a published set:**
  - Seed-TTS test-hard is Chinese only.
  - CV3-Eval `hard_en` is 64 lines of tongue twisters ([QwenAudio/CV3-Eval](https://github.com/QwenAudio/CV3-Eval), Apache-2.0); it is a usable add-on.
  - The licence for the Harvard sentences is unverified.
- **Normalization.** Apply Whisper's `EnglishTextNormalizer` to both the reference and the hypothesis ([whisper normalizers @v20250625](https://github.com/openai/whisper/blob/v20250625/whisper/normalizers/english.py)). It converts numbers, currency and British spellings.
  - Seed-TTS-eval only strips punctuation and lowercases.
  - With digits in the text, our absolute WER is therefore not comparable to Seed numbers. Only the paired deltas matter, and normalization error is identical in both arms.

## Case (a): backbone requant (`ffn_down` Q4_1→Q4_0, Q4_0 head slice)

### Variants

| Id | GGUF | Notes |
|---|---|---|
| R | published `neutts-nano-Q8_0.gguf` (sha 03ea1e93…) | Reference. On text LMs, Q8_0 KLD vs f16 is 0.0004–0.0014, while Q4_0 is 0.072, about 50× higher ([LC/tools/perplexity/README.md](https://github.com/ggml-org/llama.cpp/blob/412a363c655b479279d3050d0d1c67a5733697b4/tools/perplexity/README.md)). For an exact reference, convert the fp32 `neuphonic/neutts-nano` @ ca2ba957 (914.8 MB, gated) with `convert_hf_to_gguf.py` (untested on this vocab). |
| B | shipped `neutts-nano-Q4_0.gguf` (sha 85466ca0…) | Imatrix-quantized: its metadata carries `quantize_imatrix_file`. |
| C1 | `llama-quantize --allow-requantize --pure --token-embedding-type q8_0 --imatrix IM.gguf R.gguf C1.gguf Q4_0` | `--pure` alone would also turn `token_embd` (the tied head) into Q4_0. The token-embedding type is checked before `--pure` ([LC/src/llama-quant.cpp#L747-L790](https://github.com/ggml-org/llama.cpp/blob/412a363c655b479279d3050d0d1c67a5733697b4/src/llama-quant.cpp#L747-L790)). Quantizing from a converted fp32 GGUF instead of Q8_0 avoids double rounding: `--allow-requantize` "can severely reduce quality". |
| C2 | C1 + `--tensor-type "blk\.[0-2]\.ffn_down=q8_0"` | llama.cpp itself guards "craziness in the first few ffn_down layers … even with imatrix for Q4_0" by bumping layers < n_layer/8 ([LC/src/llama-quant.cpp#L687-L692](https://github.com/ggml-org/llama.cpp/blob/412a363c655b479279d3050d0d1c67a5733697b4/src/llama-quant.cpp#L687-L692)). Q8_0 keeps a repack layout (q8_0_4x4) where Q4_1 has none. |
| C3 | C1 or C2 with `--token-embedding-type q4_0` in place of `q8_0` | Conservative proxy for a Q4_0 head slice: it also quantizes the input embedding lookup. Measuring the head slice alone needs the JNI `score` mode (below). |

- **imatrix.** We have no copy of Neuphonic's. Without one, C differs from B in two ways at once.
  - Build one with `llama-imatrix -m R.gguf -f calib.txt --parse-special` (the flag exists for imatrix only).
  - `calib.txt` holds prompt+code lines sampled by R from text **not** in the test set: `tools/models/neutts/{passage,sentences}.txt` with the other four voices.

### Stage 1: teacher-forced KL (primary screen)

**Forced set.** `quant_kld.py sample R.gguf forced.jsonl 1`: R samples one sequence per cell, 200 sequences, ≈ 32k scored positions (66 chars average × ~2.4 codes/char measured on the passage).

**Score.** `quant_kld.py score R.gguf forced.jsonl B.gguf C1.gguf C2.gguf …` evaluates each forced sequence in each model. At every position that predicts a code or the end token, it compares the distribution over the 65,537 sampleable rows. It reports:
- mean, median, p99 and max KL(R‖X);
- same top-1 (llama.cpp's "Same top p");
- mean total-variation distance between the two distributions after the production top-k 50 → top-p 0.95 → min-p 0.05 truncation, i.e. the per-step chance that coupled samples differ;
- ln PPL ratio of the forced tokens;
- for each candidate, the 95% bootstrap CI of the mean-KLD ratio to B, paired over sequences.

**Why not `llama-perplexity --kl-divergence` directly:**
- It tokenizes without special-token parsing (`common_tokenize(ctx, prompt, true)`, `parse_special=false`, [LC/tools/perplexity/perplexity.cpp#L473](https://github.com/ggml-org/llama.cpp/blob/412a363c655b479279d3050d0d1c67a5733697b4/tools/perplexity/perplexity.cpp#L473)).
- It scores only the second half of fixed `n_ctx` chunks.
- It stores 388,520 B per scored token for our 194,256-token vocab.
- Workable alternative: write a base file yourself. The format is `_logits_`, n_ctx, n_vocab, n_chunk, int32 tokens, then uint16 log-prob records. The KLD run reads its tokens from that file, not from text (L1688-1720; [PR #5076](https://github.com/ggml-org/llama.cpp/pull/5076)).

**Why KL is a screen and not the whole gate:**
- KL has no perceptual scale for speech tokens; the llama.cpp numbers come from text LMs.
- Teacher forcing hides compounding errors in free running: loops and failure to stop.

**Gate (policy):**

| Mean-KLD ratio C/B (95% CI) | Also required | Next step |
|---|---|---|
| upper ≤ 1.0 | p99 KLD ≤ B's and same-top-1 ≥ B's − 0.5 pp | Stage 2 with M = 1, as a failure smoke test |
| upper ≤ 1.5 | — | Full Stage 2 |
| above 1.5 | — | Reject without Stage 2 |

Rank C1/C2/C3 by mean KLD and run Stage 2 only on the best passing variant.

### Stage 2: free-running outputs (acceptance)

**Arms.** B with seeds 1–8 and C with seeds 1–4, generated by `quant_kld.py sample X.gguf out.jsonl N`.
- Decode every arm with the same decoder: fp32, so that decoder error stays out of the comparison.
- That gives 200 cells × 4 seeds = **800 utterances per arm**.
- B's seeds 5–8 form an **A/A arm** that calibrates noise.

**Per-utterance metrics:**

| Metric | Tool | Notes |
|---|---|---|
| WER | faster-whisper large-v3, `beam_size=5, language="en"`, as F5-TTS does | Apply `EnglishTextNormalizer` to both sides, then jiwer `process_words`. Report corpus WER. faster-whisper avoids the HF Whisper 30 s truncation that hides runaway outputs. |
| Failure | own rule (policy) | An utterance fails if any one of these holds: no end token (`ended` false); duration outside 0.5–2× B's median for the cell (ELLA-V uses 2×); WER > 0.5 (Seed-TTS-eval tracks `wers_below50`). |
| SIM | speechbrain ECAPA `spkrec-ecapa-voxceleb`: cosine to the voice's prompt wav (`samples/{dave,jo}.wav` in the neutts zip), which is what Seed-TTS-eval and F5 pair against | Not comparable to published WavLM-large SIM. Scores differ by model: WavLM 0.753 vs ERes2Net 0.812 on the same audio ([AX 2412.10117](https://arxiv.org/abs/2412.10117) Table 6). Only deltas count. |
| UTMOS | `torch.hub.load("tarepan/SpeechMOS:v1.2.0", "utmos22_strong")`, as F5-TTS does | Utterance SRCC 0.881, system SRCC 0.925 ([AX 2204.02152](https://arxiv.org/abs/2204.02152)). Use only as an average. |

**Analysis.**
- Average each cell over its seeds.
- Compute Δ = C − B with a paired bootstrap over sentences (10,000 resamples, 95% CI). WER uses the difference of error counts on identical resamples (Bisani & Ney).

**Accept when all of these hold:**
- ΔUTMOS CI ⊂ [−0.05, +0.05] and ΔWER upper ≤ +0.01 absolute (Park et al.).
- Δfailure-rate upper ≤ +1 pp (policy).
- ΔSIM lower ≥ −0.01 (policy).

**Power and noise checks.**
- **A/A first.** Each A/A Δ CI must sit inside its margin. If it does not, the run cannot resolve the margin: raise M before judging C.
- **Failure-rate power.** Detecting a rise from 1% to 3% at α 0.05 with power 0.8 needs ≈ 765 utterances per arm (two-proportion normal approximation). That is why we use 800.

### Device parity (optional for a, required for c)

- **Why.** Host scoring uses x86 kernels and llama-cpp-python 0.3.36's bundled llama.cpp (0c1e570, 417 commits behind our b11729). The app runs ARM `q4_0_4x4` repack kernels. Weight-quantization error should dominate, but this is unverified.
- **Check.** Compute KL(host X‖device X) over 10 forced sequences. It must be < 10% of KL(R‖C).
- **Needs (not implemented):** a JNI/CLI `score <modelDir> forced.jsonl out.bin` mode. It would:
  - prefill the prompt;
  - feed each forced code with `llama_decode`;
  - run `computeHead`;
  - write the 65,537 head logits per step as fp16 (≈ 128 KB/step).

  It reuses `checkHead`'s loop in `app/src/main/cpp/neutts_jni.cpp`. The same mode measures C3 exactly.

## Case (b): decoder fp32 convs → fp16 or int8

Run once (2026-10-20), for the NeuTTS int8-conv decoder `mm-pc` that ships in catalog revision `neutts-nano-q4-ac69851f-v2`: all gates passed; the listening check was informal (one listener, no difference heard), not the 16-trial ABX. Results: `docs/NEUTTS-PHONE-BENCH.md` ("Decoder int8 convs").

**Variants.**

| Id | Decoder |
|---|---|
| F | fp32 `neucodec-onnx-decoder` (sha 2a43d034…), the reference |
| B | shipped int8 (sha 3ddd9e56…; int8 MatMulInteger, fp32 convs), the anchor |
| C | B with the 9 conv weights in fp16, or B with per-channel int8 (static QDQ calibrated on real codes) |

**Run conditions.**
- Evaluate each ONNX file with the app's session options (`ORT_ENABLE_ALL`). Fusions such as `DynamicQuantizeMatMul` change numerics.
- Use the onnxruntime version closest to the app's 1.28.2 that PyPI offers; 1.31.0 is current.

**Inputs.** Real codes only: the 200 Stage 1 forced sequences plus the 6 voices' reference codes (real speech), 206 clips in all.
- Random codes, as used in `NEUTTS-PHONE-BENCH.md` for timing, are off-distribution and invalid for quality.
- Decode one-shot. Streaming chunking is item 2's separate gate.
- The decoder is deterministic, so no seeds are needed.

**Why int8 convs are the risky change.** Park et al. found:
- ORT dynamic int8 with per-tensor activation scales took Supertonic WER from 0.028 to 1.11;
- "int8 restricted to the MatMul operators and the convolutions left in fp32" was quality-preserving;
- "The waveform decoder or codec is the first component to test" ([AX 2609.28974](https://arxiv.org/abs/2609.28974)).

**Metrics per clip.** All are measured against F's output; the clips are time-aligned.
- **SNR** (full 24 kHz band) and multi-resolution STFT distance (auraloss defaults, the Parallel WaveGAN resolutions, [AX 1910.11480](https://arxiv.org/abs/1910.11480)) plus log-mel L1. These are regression detectors, valid here because both outputs come from one generator with one phase.
  - Anchor: in the litert-community/Mimi card, fp16-exact convs measured 48 dB SNR, while an fp16 precision failure measured ~12 dB ([HF card](https://huggingface.co/litert-community/Mimi)).
- **Residual in pauses:** the RMS of (X − F) over frames where F is below −50 dBFS. Quantization noise is most exposed in silence.
- **ViSQOL v3, audio mode** (full band, 48 kHz input; identical signals score ≈ 4.73).
  - Speech mode and PESQ only see ≤ 8 kHz.
  - PESQ is also withdrawn: ITU-T deleted P.862/P.862.2 on 2024-01-05 ([ITU](https://www.itu.int/rec/T-REC-P.862.2)), and `pesq` 0.0.4 needs MSVC on Windows. Skip it.
- **Downstream deltas** with the case (a) tools: ΔUTMOS, ΔWER and ΔSIM, paired per clip, C vs B.

**Accept when all of these hold:**
1. **Total error.** Median and worst-decile SNR(C vs F) ≥ SNR(B vs F) − 1 dB, and pause residual(C) ≤ residual(B) + 1 dB.
   - So the conv error may raise total error power by at most ≈ 26%. That is about the level JND for broadband noise, 0.5–1 dB ([AX 2307.00084](https://arxiv.org/abs/2307.00084)) (policy).
2. **ViSQOL.** The paired-bootstrap lower bound of ViSQOL(C vs F) − ViSQOL(B vs F) is ≥ −0.05 (policy, mirroring the UTMOS margin).
   - No source declares a "transparent" ViSQOL or PESQ delta.
3. **Downstream.** ΔUTMOS CI ⊂ [−0.05, 0.05], ΔWER upper ≤ 0.01 (Park et al.), and ΔSIM lower ≥ −0.01 (policy).
4. **Listening check, for int8 convs or any metric within 0.02 of its margin.** Run an ABX on the 10 clips with the largest MR-STFT distance, on headphones, 16 trials per listener.
   - ≥ 12/16 correct counts as audible (one-sided p = 0.038) and means reject.
   - ITU-R BS.1116-3 (double-blind triple-stimulus with hidden reference, about 20 subjects) is the formal arbiter for small impairments ([BS.1116-3](https://www.itu.int/rec/R-REC-BS.1116)). It is impractical here.

## Case (c): engine or quant-format swap (e.g. MNN 4-bit vs llama.cpp Q4_0)

An engine swap changes four things at once:
- the weight scheme (MNN `llmexport` defaults: 4-bit, asymmetric, block 64, fp16 scales; [MNN llmexport.py](https://github.com/alibaba/MNN/blob/d662ce36175956038cfd6ed13f84cb3343a287d2/transformers/llm/export/llmexport.py) L1014-L1034);
- the device compute numerics;
- the tokenizer and template;
- the sampler.

Steps:
1. **Tokenizer parity.** Prompt token ids must match exactly for all 200 prompts.
2. **KL on device.**
   - Score both engines on the device, against R, over the Stage 1 forced set.
   - For MNN, `Llm::forward(ids)` with `{"all_logits":true}` returns per-position logits (`transformers/llm/engine/tools/ppl_eval.cpp`).
   - For B, use the JNI `score` mode.
   - Gate: same as case (a), with B being the shipped llama.cpp Q4_0 on device.
3. **Sampler.** Drive our sampler (speech rows, top-k → top-p → min-p) from the engine's logits, so only the logits differ.
4. **Full Stage 2, no reduced path.**
   - Generate on device unless host-vs-device KL for that engine passes the parity check.
   - On the phone this costs ≈ 70 min per arm (800 × ~5 s at RTF ≈ 1) plus cool-downs (estimate).

## Tools and pins (Windows host, Python ≥ 3.12)

| Purpose | Package / model | Pin | Notes |
|---|---|---|---|
| LM logits, sampling | llama-cpp-python | 0.3.36 | CPU wheel from `--extra-index-url https://abetlen.github.io/llama-cpp-python/whl/cpu` (py3-none-win_amd64). `Llama(logits_all=True)` holds n_ctx × n_vocab fp32 = 1.6 GB per loaded model. |
| G2P | phonemizer + neutts 1.4.1 wheel's `espeak-ng.dll` | 3.4.0 | Same setup as `phoneme_parity.py`. |
| Quantize, imatrix | llama.cpp tools | b11729 | Official release binaries. |
| Decoder | onnxruntime | 1.31.0 (or the app's 1.28.x if available) | |
| ASR | faster-whisper + ctranslate2, `large-v3` (≈ 3 GB) | 1.2.1, 4.8.3 | GPU fp16 on the RTX 4060 needs the CUDA 12 cuBLAS/cuDNN DLLs; CPU `int8` works without them. |
| Normalizer | openai-whisper (`whisper.normalizers`) | 20250625 | |
| WER | jiwer | 4.0.0 | `compute_measures` was removed in 4.0, so use `process_words`. |
| SIM | speechbrain, `speechbrain/spkrec-ecapa-voxceleb` | 1.1.2 | Already used in this repo for accent ID (`tools/models/neutts/REPRODUCE.md`). |
| MOS | torch.hub `tarepan/SpeechMOS:v1.2.0` `utmos22_strong` | tag v1.2.0 | PyPI `speechmos` is an unrelated package. |
| Full-band ref metric | visqol-python | 3.8.0 | Pure Python; "12/12 conformance tests pass against the official C++". |
| Spectral distances | auraloss, librosa, soundfile | 0.4.0, 1.0.0, 0.14.0 | |
| Torch | torch, torchaudio | 2.11.0 | |

**Run form.** One-off envs: `uv run --no-project --with <pkg>==<ver> … python -I <script>`. Use `uv run python -m <module>` for module entry points.

**Host vs phone:**
- **Host:** everything above.
- **Phone:**
  - the device-parity KL (needs the JNI `score` mode);
  - case (c) logits and generation;
  - optional listening to device output.
- ASR, SIM, MOS and ViSQOL never run on the phone.

## Cost estimates (host: Ryzen 9 7945HX, RTX 4060 8 GB; not measured)

| Step | Work | Estimate |
|---|---|---|
| imatrix + quantize C1–C3 | 4 GGUFs | 15 min |
| Stage 1 sample | 200 sequences with R | 10 min |
| Stage 1 score | 200 sequences × (R + B + 3 C); about 8 GB RAM if all are loaded | 20–30 min |
| Stage 2 generation | 2,400 utterances (B × 8 seeds, C × 4) at an assumed ≥ 150 tok/s on 8 Zen 4 threads | 1.5–2 h |
| Stage 2 decode | fp32 ONNX on CPU | 15 min |
| ASR | large-v3 on GPU fp16, 2,400 utterances (≈ 3.3 h of audio) | 10–15 min (CPU int8: about 1–1.5 h) |
| SIM + UTMOS | GPU | 10 min |
| Case (b) | 206 clips × 3–4 decoders + ViSQOL | 30 min |
| ABX (if triggered) | 3 listeners × 16 trials | 30 min of people |
| Downloads | large-v3 3 GB, torch CUDA ≈ 2.5 GB, R and fp32 decoder ≈ 1 GB, UTMOS/ECAPA < 0.5 GB | — |

**Totals:** case (a) ≈ 3–4 h wall, mostly unattended. Case (b) ≈ 1 h. Case (c) adds ≈ 3 h of phone time.

## Open questions

1. Is Neuphonic's imatrix (or its calibration text) obtainable? Otherwise the B vs C comparison also includes an imatrix difference.
2. Does `convert_hf_to_gguf.py` at b11729 convert `neutts-nano` without a tokenizer pre-hash patch? This decides whether R can be fp32 rather than Q8_0.
3. Should the JNI `score` mode be built now? It is needed for exact C3, device parity and case (c).
