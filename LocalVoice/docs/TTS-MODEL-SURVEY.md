# On-device TTS model survey (2026-10-19)

**Scope:** models newer than, or better than, the shipped set that can run on a phone CPU through sherpa-onnx or a small custom adapter.

**Shipped set** (per `app/src/main/resources/org/localvoice/tts/models.json`):
- Kokoro v1.0 int8
- Supertonic 3 int8
- Pocket TTS English `soniqo` int8, from historical checkpoint `tts_b6369a24`
- Shtorm Pocket RU v2-fast

**Targets:** Pixel 7 Pro (Tensor G2) and Huawei Pura 70.

**Ranking criteria:** quality, then phone viability (size, CPU RTF, streaming and first-audio latency, Android runtime and integration effort).
- Licences are listed for information only. Per the owner's 2026-10-19 decision, non-commercial, research-only and revenue-capped licences do not disqualify a model.
- This project has not measured any phone RTF. Vendor speed figures are mostly from desktop or Apple CPUs.

## Recommendation

No open model from 2025–2026 is both clearly better than Kokoro v1.0 / Pocket TTS and fast enough for a phone CPU. Ignoring licences does not change that: every model that had been skipped for its licence also fails on size or speed, or has worse quality.

The gains available:
- a newer Pocket checkpoint;
- low-effort extra or fallback English voices;
- for Russian, stress placement in front of Shtorm, or Silero v5 if its frontend can be ported.

**English**

1. **Adopt: Pocket TTS English `english_2026-09_2`** (replaces the shipped `tts_b6369a24` export).
   - Kyutai's default `english` model since 2026-10-11. ([PR #353](https://github.com/kyutai-labs/pocket-tts/pull/353))
   - Reported WER 0.87–0.93%, UTMOS 4.36, speaker similarity 0.922.
   - Fixes `english_2026-09`'s sentence-end repeats and flat questions.
   - About 100M parameters, streams.
   - Integration:
     - Uses the same LSD recipe as `english_2026-09`, so the existing `sherpa-pocket` adapter should fit (unverified).
     - Needs a project-made ONNX export: sherpa-onnx ships only the `pocket-tts-2026-01-26` export ([tts-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models)).
     - Avoid `english_drifting_26-09`: its new `flow.type: drifting` head changes the graph ([PR #329](https://github.com/kyutai-labs/pocket-tts/pull/329)).
2. **Evaluate: Kitten TTS mini v0.8.**
   - 80M parameters / about 80 MB, 8 English voices.
   - Runs on the shipped sherpa-onnx 1.13.8 AAR through the existing Kitten config.
   - No first-party quality or speed numbers. Treat it as a second voice family, not a Kokoro replacement.
3. **Evaluate: Inflect Micro v2.**
   - About 9.4M parameters, one male voice, runs on the shipped AAR.
   - Reports UTMOS22 4.395 (predicted, not human MOS), WER 3.99%, RTF 0.159 on 4 desktop threads.
   - A near-zero-cost fallback for slow or hot phones.
4. **Evaluate, high effort: NeuTTS Nano.**
   - Previously skipped partly for its licence; now ranked on speed and effort alone.
   - Q4 backbone: 45 tok/s on a Galaxy A25 CPU against the 50 Hz codec rate, plus codec decode time. A Tensor G2 should be faster than the A25 (unverified).
   - Has voice cloning, but no published quality evals.
   - Needs llama.cpp plus an ONNX NeuCodec decoder plus a phonemizer. Do it only if items 1–3 disappoint.

**Russian top pick: keep Shtorm Pocket RU v2 and add automatic stress placement** (RUAccent, ONNX; or silero-stress) in front of it.
- Shtorm's card expects acute stress marks. The current frontend preserves them but does not generate them (`docs/SHTORM-FRONTEND.md`).
- **Runner-up, now eligible: Silero `v5_6_ru`.** Built-in auto-stress, homographs, questions and emphasis; 5 speakers; 48 kHz; 147.7 MB.
  - Fast: Silero reports 36× real time on one desktop thread, measured on `v5_cis_base`.
  - Integration is the blocker. It ships only as a `torch.package` whose frontend is Python, so it needs LibTorch/ExecuTorch on Android plus a reimplemented or exported frontend, or an ONNX extraction of its networks. Feasibility is unverified.
- **Zero-effort fallback:** Piper `ru_RU-ruslan` (also denis/dmitri) via sherpa.
- Also try the Shtorm v2 **slow** variant as a second voice.

**Watch, no action now**
- Soprano-1.1-80M: streaming, claims 20× real time on CPU, no ARM data.
- Chatterbox-Nano: about 3× real time on 8 desktop cores, no official ONNX.
- Kokoro 7M distills: sherpa master only.

## Comparison table

- RTF = compute ÷ audio; below 1 is faster than real time.
- "1.13.8 ✓" means the shipped AAR loads the model with no runtime change.
- The Licence column is informational.

| Candidate | Params / size | Lang · voices | Quality evidence | Reported speed | Streaming | Runtime / effort | Licence (info) | Verdict |
|---|---|---|---|---|---|---|---|---|
| Pocket TTS `english_2026-09_2` | ~100M | EN · 27 preset voices | WER 0.87–0.93%, UTMOS 4.36, SIM 0.922 (1st-party) | ~6× real time on 2 M4 cores | yes, ~200 ms first chunk | sherpa `pocket` (1.12.24+); needs re-export | CC-BY-4.0; some voices CC BY-NC | **Adopt** |
| Kitten mini v0.8 | 80M / ~80 MB | EN · 8 | none published | none | sentence | 1.13.8 ✓ | Apache-2.0 | **Evaluate** |
| Kitten micro / nano v0.8 | 40M / 41 MB; 15M / 25 MB | EN · 8 | nano WER 2.15–2.39% (Inflect card) | nano 13.3× real time (desktop) | sentence | 1.13.8 ✓ | Apache-2.0 | Evaluate (low end) |
| Inflect Micro v2 | 9.4M / 37.5 MB | EN · 1 | UTMOS22 4.395, WER 3.99% (1st-party) | RTF 0.159, 4 vCPU | no (chunked) | 1.13.8 ✓ (`vits`, 1.13.5+) | Apache-2.0 | **Evaluate** |
| NeuTTS Nano | 229M (117M active) | EN (+ES/FR/DE variants) · cloning | none published | Q4 LM 45 tok/s, Galaxy A25 (50 needed) | yes (GGUF) | llama.cpp + ONNX NeuCodec; high effort | NeuTTS Open License 1.0 | Evaluate (high effort) |
| NeuTTS Air | 552M (360M active) | EN · cloning | none published | Q4 LM 20 tok/s, Galaxy A25 | yes (GGUF) | llama.cpp + ONNX NeuCodec | Apache-2.0 | Skip (speed) |
| Kokoro v1.1-zh | 82M | EN +3 voices; mainly ZH | "not a strict upgrade" (card) | — | segment | 1.13.8 ✓ | Apache-2.0 | Skip (extra voices only) |
| Kokoro-7M-Distill | 7.5M / 8.7 MB int8 | EN · 1 | none | RTF 0.022 (desktop) | segment | Kokoro config; scripts on sherpa master | Apache-2.0 | Watch |
| Soprano-1.1-80M | 80M | EN · 1 | 1st-party preference only | "20× real time CPU", <250 ms | yes | custom ONNX (3rd-party port) | Apache-2.0 | Watch |
| Chatterbox-Nano | 110M | EN (unstated) · cloning | none for Nano | 3× real time, 8 CPU threads | not stated | custom LM + decoder | MIT, gated, watermark | Watch |
| Supertonic 3 (shipped) | ~99M | 31 incl. RU | WER/CER chart only | RTF 0.3 on Boox e-reader | no | 1.13.2+ | OpenRAIL-M | Keep; archived, no v4 |
| Piper EN voices (e.g. lessac-high) | 15–60M | EN · ~43 | older VITS | fast | no | 1.13.8 ✓ | GPL code; per-voice data (lessac NC) | Skip (quality < Kokoro) |
| MeloTTS EN | VITS | EN accents | — | "CPU real-time" | no | sherpa (zh_en; unverified) | MIT | Skip (quality) |
| Matcha (LJSpeech) | small | EN · 1 | older | — | no | 1.13.8 ✓ | MIT code | Skip (quality) |
| ZipVoice-distill | 123M | ZH/EN, cloning only | — | — | no | sherpa 1.12.11+ | Apache-2.0 code | Skip (needs reference audio) |
| F5-TTS (incl. 158M "small") | 335M; small = paper-only | EN/ZH · cloning | 1st-party paper | not real time on CPU (ONNX port, M1) | no | custom; multi-step DiT | CC-BY-NC weights | Skip (speed; no small weights) |
| StyleTTS2-lite | ~90M (unverified) | EN | — | — | no | custom | MIT | Skip (Kokoro is the productised StyleTTS2) |
| Silero `v3_en` | 57 MB | EN · 118 | none published | fast | no | torch.package | CC BY-NC | Skip (old; integration as Silero RU) |
| Spark-TTS 0.5B, Llasa-1B, Kitten TTS 2 (1.7B), XTTS-v2 | 0.5–1.7B | multi (XTTS, Kitten 2 incl. RU) | — | GPU-class | varies | custom LLM + codec | NC / CPML / Stellon cap | Skip (size/speed) |
| Orpheus, CSM-1B, Dia2, Zonos, OuteTTS, Kyutai TTS 1.6B | 0.6–3B | EN (+) | — | GPU-class | yes | custom | permissive | Skip (size; no small variants released) |
| VibeVoice-Realtime-0.5B | 0.5B (~1B on HF) | EN | — | ~30 s/sentence, Pixel 8a (LiteRT) | yes | custom | MIT, "research purposes" | Skip (speed) |
| Qwen3-TTS 0.6B | 0.6B (0.9B on HF) | 10 incl. RU | 1st-party paper | 97 ms (GPU) | yes | llama.cpp `tools/tts` | Apache-2.0 | Skip (phone CPU) |
| Magpie TTS 357M | 364M | 12, no RU · 5 EN | — | GPU/Tegra only | no | NeMo / GGUF | NVIDIA Open Model License | Skip (no CPU data, effort) |
| **Silero `v5_6_ru`** | 147.7 MB | RU · 5, auto-stress + homographs | no v5_ru numbers; cis_base RU UTMOS 3.04 vs 3.08 recordings | 1/RTF 36.5 on 1 i9 thread (cis_base) | no | torch.package (Python frontend); high effort | CC BY-NC | **Evaluate (RU #2)** |
| Silero `v5_cis_base` | — | RU via `ru_` speakers; needs `+` stress | RU UTMOS 3.04 | same | no | torch.package | MIT | Skip (v5_6_ru dominates) |
| Shtorm v2 slow (RU) | ~100M | RU | v1 card: CER 1.6/4.0%, UTMOS 3.64/3.52 | CPU-capable | yes | existing Shtorm adapter | CC-BY-4.0 | Evaluate |
| Piper ru ruslan / denis / dmitri | medium VITS | RU · 1 each | below current | fast | no | 1.13.8 ✓ | ruslan CC BY-NC-SA; others CC0 data | Fallback |
| MMS `mms-tts-rus` | ~36M VITS | RU · 1, 16 kHz | — | fast | no | 1.13.8 ✓ (`vits-mms-rus`) | CC-BY-NC | Skip (16 kHz, quality) |
| Vosk TTS ru 0.9-multi | 783 MB zip | RU · 5 | harness, no results | — | no | ORT VITS + BERT frontend | code Apache-2.0; weights unstated | Evaluate later |
| F5 RU (Misha24-10), ESpeech-TTS-1, Shtorm-VoxCPM2 | 0.3–2B | RU | — | GPU | no | custom | NC / Apache-2.0 | Skip (speed) |

## Per-candidate notes

### Runtime: sherpa-onnx

- **Latest release:** 1.13.8 (2026-09-10); nothing newer. Master head is `1a9d234` (2026-10-17). ([releases API](https://api.github.com/repos/k2-fsa/sherpa-onnx/releases))
- **TTS families in source:** vits (Piper, MMS, Inflect), matcha, kokoro, kitten, zipvoice, pocket, supertonic, plus a MeloTTS lexicon. ([csrc](https://github.com/k2-fsa/sherpa-onnx/tree/master/sherpa-onnx/csrc))
- **Version that added each family** ([CHANGELOG](https://github.com/k2-fsa/sherpa-onnx/blob/master/CHANGELOG.md)):
  - Kitten 1.12.8
  - ZipVoice 1.12.11
  - Pocket 1.12.24
  - Supertonic 1/2 1.12.29
  - Supertonic 3 and Kitten v0.8 1.13.2
  - Inflect and VITS `emotion_id` 1.13.5
  - Kokoro `em_santa` and Nabra-82M Arabic 1.13.8
- **Merged after 1.13.8, on master only:**
  - Kokoro 7M-distill conversion scripts ([#3954](https://github.com/k2-fsa/sherpa-onnx/pull/3954))
  - Supertonic `"na"` language tag (#3958)
  - JNI converts C++ exceptions to Java exceptions (#3988), worth picking up in the next AAR
- **No accelerator path for TTS:** no NNAPI, GPU, QNN or NPU support. The documented accelerator providers are ASR-only. ([TTS docs](https://k2-fsa.github.io/sherpa/onnx/tts/index.html))

### Kokoro

- **Versions:**
  - v1.0 (2025-01-27) remains the newest English Kokoro. ([card](https://huggingface.co/hexgrad/Kokoro-82M))
  - v1.1-zh adds af_maple, af_sol and bf_vale but drops other voices. ([card](https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh))
- **Leaderboards:**
  - TTS Arena V2 ([leaderboard JSON](https://tts-agi-tts-arena-v2.hf.space/api/leaderboard), fetched 2026-10-19): Kokoro v1.0 (ELO 1473) is the top open-weights row. Chatterbox (1476) is flagged not open. Kitten, Supertonic and Pocket TTS are not listed.
  - Artificial Analysis also ranks Kokoro (1064) above Chatterbox (1026), VibeVoice 1.5B and Qwen3-TTS. ([leaderboard](https://artificialanalysis.ai/text-to-speech/leaderboard))
- **Phone speed signal:** LiteRT-LM Kokoro int8 runs at CPU RTF 0.94 on a Pixel 10 Pro (4 threads) against 0.26 on a Galaxy S26, with about 975 MB peak memory. ([litert-community/Kokoro-82M](https://huggingface.co/litert-community/Kokoro-82M)) Tensor CPUs are slow for Kokoro, so measure Tensor G2 before keeping Kokoro as the default English voice.
- **Kokoro-7M-Distill:** 7.48M parameters, one voice, desktop RTF 0.022, no quality data. ([card](https://huggingface.co/oddadmix/Kokoro-7M-Distill))

### Kyutai Pocket TTS

- **Basics:** code MIT, weights CC-BY-4.0; `kyutai/pocket-tts-without-voice-cloning` is ungated. Streams, about 200 ms to the first chunk, about 6× real time on 2 M4 cores. ([repo](https://github.com/kyutai-labs/pocket-tts), [card](https://huggingface.co/kyutai/pocket-tts))
- **English checkpoints, in order:**
  - `english_2026-04`
  - `english_2026-09` (LSD)
  - `english_drifting_26-09`: one-step head, WER 0.90%, UTMOS 4.37, new config type ([#329](https://github.com/kyutai-labs/pocket-tts/pull/329))
  - `english_2026-09_2`: default since 2026-10-11 ([#353](https://github.com/kyutai-labs/pocket-tts/pull/353)). Human A/B against `english_2026-09` was a 102–98 tie; the gains are fewer repeats and better question intonation.
- **Training code** (v3.0.0, 2026-08-25) documents fine-tuning into a new language. That makes a project-owned Russian fine-tune possible.
- **Languages:** EN, FR, DE, PT, IT, ES, NL. No Russian.
- **Voices:** Expresso- and EARS-derived voices are CC BY-NC (now allowed). alba, charles, mary and eve are CC BY 4.0; marius and javert are CC0. Source: [Pocket-TTS-LiteRT card](https://huggingface.co/mlboydaisuke/Pocket-TTS-LiteRT).
- **CPU vs GPU on Pixel 10:** the same LiteRT port measures 20.5 ms per LM step on CPU against 50.8 ms on GPU, so CPU ONNX is the right path.
- **Kyutai TTS 1.6B / 0.75B:** server-sized. ([card](https://huggingface.co/kyutai/tts-1.6b-en_fr))

### Supertonic

- v3 (2026-04-29): about 99M parameters, 31 languages, expression tags. ([card](https://huggingface.co/Supertone/supertonic-3))
- Project archived 2026-09-09: "Development and support have ended". No v4. ([GitHub](https://github.com/supertone-oss-archive/supertonic))

### Kitten TTS and Inflect

- **Kitten v0.8** (nano 15M, micro 40M, mini 80M):
  - English, 8 voices, 24 kHz, eSpeak frontend shared with Kokoro. ([GitHub](https://github.com/KittenML/KittenTTS), [mini](https://huggingface.co/KittenML/kitten-tts-mini-0.8), [micro](https://huggingface.co/KittenML/kitten-tts-micro-0.8))
  - sherpa ships `kitten-{nano,micro,mini}-en-v0_8` with an Android APK. ([docs](https://k2-fsa.github.io/sherpa/onnx/tts/all/English/kitten-mini-en-v0_8.html))
- **Kitten TTS 2:** 1.7B with Russian, so too large for a phone. ([card](https://huggingface.co/KittenML/kitten-tts-2))
- **Inflect Micro v2:**
  - Card: [Inflect-Micro-v2](https://huggingface.co/owensong/Inflect-Micro-v2); sherpa: [vits-inflect-en-micro-v2](https://k2-fsa.github.io/sherpa/onnx/tts/all/English/vits-inflect-en-micro-v2.html).
  - The Nano v2 (4M) figures come only from a mirror and are unverified.

### LLM and codec TTS (re-evaluated without licence filter)

- **NeuTTS** ([repo](https://github.com/neuphonic/neutts), [Nano card](https://huggingface.co/neuphonic/neutts-nano)):
  - Speed: Q4_0 LM-only on a Galaxy A25 CPU is 20 tok/s for Air and 45 tok/s for Nano; on a Ryzen HX 370 it is 119 and 221.
  - NeuCodec runs at 50 Hz, so real time needs ≥50 tok/s plus codec decode.
  - Streaming is GGUF-only. The ONNX codec is decode-only and needs pre-encoded reference voices. The context is 2048 tokens (about 30 s including the prompt).
  - Output is watermarked. No quality evals.
  - Nano is the only LLM-codec model plausibly near real time on a Tensor G2.
- **Chatterbox-Nano:**
  - 110M parameters, about 3× real time on 8 CPU threads, no official ONNX. ([page](https://www.resemble.ai/learn/models/chatterbox-nano), [README](https://github.com/resemble-ai/chatterbox))
  - Turbo (350M) and Multilingual V3 (500M, includes RU) are too heavy for phone CPU.
- **Soprano 1.1:**
  - About 80M parameters, 32 kHz, streaming.
  - Claims up to 20× real time on CPU with under 250 ms latency on CPU; hardware not stated. ([card](https://huggingface.co/ekwek/Soprano-80M), [GitHub](https://github.com/ekwek1/soprano))
- **Orpheus:** 150M, 400M and 1B are still unticked roadmap items; only 3B exists. ([GitHub](https://github.com/canopyai/Orpheus-TTS))
- **Sesame CSM:** only CSM-1B (about 2B tensors, CUDA-oriented). ([card](https://huggingface.co/sesame/csm-1b))
- **F5-TTS:**
  - The 158M "small" model exists only as a paper ablation trained on LibriTTS/LJSpeech, with no released checkpoint found. ([arXiv 2410.06885](https://arxiv.org/abs/2410.06885), [GitHub](https://github.com/SWivid/F5-TTS))
  - Base is multi-step DiT flow matching; a third-party ONNX port is not real time on an M1 CPU. ([IndicF5-ONNX](https://huggingface.co/developerabu/IndicF5-ONNX))
- **StyleTTS2-lite:** no sherpa support, and Kokoro already covers this family. ([card](https://huggingface.co/dangtr0408/StyleTTS2-lite))
- **Other large models (size-limited):**
  - Spark-TTS 0.5B ([card](https://huggingface.co/SparkAudio/Spark-TTS-0.5B))
  - Llasa-1B ([card](https://huggingface.co/HKUSTAudio/Llasa-1B))
  - XTTS-v2, GPT-based, last updated 2023-12 ([card](https://huggingface.co/coqui/XTTS-v2))
  - VibeVoice-Realtime, with a Pixel 8a LiteRT port at about 30 s per sentence ([card](https://huggingface.co/microsoft/VibeVoice-Realtime-0.5B), [port](https://huggingface.co/mlboydaisuke/VibeVoice-Realtime-0.5B-LiteRT))
- **Vendors:**
  - Google: no open on-device TTS release.
  - Apple, Meta, IBM: none found.
  - NVIDIA Magpie 357M: no RU, no CPU data. ([card](https://huggingface.co/nvidia/magpie_tts_multilingual_357m))
- **Runtimes:**
  - llama.cpp `tools/tts` supports Qwen3-TTS and Pocket TTS GGUF, with no Android notes. ([tools/tts](https://github.com/ggml-org/llama.cpp/tree/master/tools/tts))
  - No official ExecuTorch Android TTS example found.

### Piper, Matcha, MeloTTS

- Piper moved to [OHF-Voice/piper1-gpl](https://github.com/OHF-Voice/piper1-gpl). NC voices such as en_US-lessac-high ([MODEL_CARD](https://huggingface.co/rhasspy/piper-voices/blob/main/en/en_US/lessac/high/MODEL_CARD)) are now eligible, but they are still VITS quality, below Kokoro.
- Matcha ([GitHub](https://github.com/shivammehta25/Matcha-TTS)) and MeloTTS ([GitHub](https://github.com/myshell-ai/MeloTTS)) are the same generation as Kokoro or older.

### Russian

- **Shtorm-PocketTTS-RU** ([card](https://huggingface.co/ArtShtorm/Shtorm-PocketTTS-RU)):
  - v2 is dated 2026-10-01 (card revision `33f6c328`); fast and slow variants.
  - Input contract: acute (U+0301) stress marks, Cyrillic-only text, chunks of 120–180 characters.
  - The shipped `2026-10-01-fast` revision matches v2-fast.
- **Stress frontends:**
  - RUAccent runs on ONNX Runtime ([GitHub](https://github.com/Den4ikAI/ruaccent), [HF](https://huggingface.co/ruaccent/accentuator)).
  - silero-stress is torch-only and about 50 MB. Homograph F1 0.92; 60–70% accuracy on unknown words. ([GitHub](https://github.com/snakers4/silero-stress))
  - Both emit `+`, which must be mapped to U+0301 for Shtorm.
- **Silero v5 RU:**
  - Model line `v5_ru` … `v5_6_ru`, speakers aidar, baya, kseniya, xenia and eugene, 8/24/48 kHz. ([README](https://github.com/snakers4/silero-models), [models.yml](https://github.com/snakers4/silero-models/blob/master/models.yml))
  - Features, added by version: auto-stress and homographs from v5_ru; questions from v5_4; emphasis from v5_6.
  - Packaging: `.pt` only, no ONNX, loaded via `torch.package.PackageImporter(...).load_pickle(...)`.
  - The `v5_6_ru.pt` package is 147,664,410 bytes, Last-Modified 2026-10-15 (HTTP HEAD).
  - Speed: 1/RTF 36.48 on 1 thread and 85.72 on 4 threads of an i9-10940X. That page reports only `v5_cis_base` quality (RU UTMOS 3.04 ± 0.02 against 3.08 for recordings), so the speed figure is assumed to be the same model. ([wiki speed/quality page](https://github.com/snakers4/silero-models/wiki/%D0%9F%D1%83%D0%B1%D0%BB%D0%B8%D1%87%D0%BD%D0%B0%D1%8F-%D0%B4%D0%BE%D0%BA%D1%83%D0%BC%D0%B5%D0%BD%D1%82%D0%B0%D1%86%D0%B8%D1%8F-%D0%BF%D0%BE-%D1%81%D0%BA%D0%BE%D1%80%D0%BE%D1%81%D1%82%D0%B8-%D0%B8-%D0%BA%D0%B0%D1%87%D0%B5%D1%81%D1%82%D0%B2%D1%83-%D1%80%D0%B0%D0%B1%D0%BE%D1%82%D1%8B))
  - No Silero Android or mobile path is documented.
- **Silero English:** `v3_en` (118 speakers, 57 MB) is the only English Silero line; there is no v4/v5 English.
- **Vosk TTS:**
  - `vosk-model-tts-ru-0.9-multi`: 5 speakers, VITS ONNX plus a BERT prosody model plus a stress dictionary, not in sherpa. ([GitHub](https://github.com/alphacep/vosk-tts))
  - The repo includes a Russian benchmark harness but no published results.
- **Piper ru:** ruslan, denis, dmitri and irina are all in sherpa `tts-models`, all medium VITS at 22.05 kHz. ([voices](https://huggingface.co/rhasspy/piper-voices/tree/main/ru/ru_RU))
- **MMS rus:** single speaker, 16 kHz. ([config](https://huggingface.co/facebook/mms-tts-rus/blob/main/config.json))
- **F5 RU:** Misha24-10 ([card](https://huggingface.co/Misha24-10/F5-TTS_RUSSIAN)) and ESpeech-TTS-1 ([card](https://huggingface.co/ESpeech/ESpeech-TTS-1_RL-V2)) are F5 DiT models and too slow for a phone CPU.
- **Other models with Russian:**
  - Shtorm-VoxCPM2-RU: 2B, GPU ([card](https://huggingface.co/ArtShtorm/Shtorm-VoxCPM2-RU))
  - Qwen3-TTS 0.6B: LLM-sized ([card](https://huggingface.co/Qwen/Qwen3-TTS-12Hz-0.6B-Base))
  - Kokoro: no Russian model found

## Unverified / open questions

- **Phone measurement is the deciding gate.** No candidate has a first-party phone RTF. Measure on Pixel 7 Pro and Pura 70 with the same text:
  - Kokoro int8
  - Pocket `english_2026-09_2`
  - Kitten mini
  - Inflect Micro
  - NeuTTS Nano Q4 (LM tok/s plus NeuCodec ONNX decode)
  - Shtorm slow
- **Pocket `english_2026-09_2` export:** does it fit the sherpa `pocket` / soniqo int8 graph layout? It uses the same LSD recipe, but nobody has exported it. Its voice embeddings were recomputed and must be re-exported too.
- **Silero `v5_6_ru` on Android:**
  - Can the `torch.package` be split into TorchScript networks (then ExecuTorch, LibTorch-lite or ONNX) with the Python frontend reimplemented in Java, including stress and homographs?
  - Its quality against Shtorm v2 is unmeasured; no first-party numbers exist for v5_ru.
- **NeuTTS Nano:**
  - Real Tensor G2 throughput, NeuCodec ONNX decode cost, and first-audio latency.
  - Fixed voices require pre-encoded references, which is fine for preset voices.
- **Missing listening data:** TTS Arena V2 does not list Kitten, Inflect, Soprano, NeuTTS (open), Supertonic or Pocket TTS, and no blind listening test against Kokoro was found.
- **Single-source claims:**
  - Kitten v0.8 and Inflect v2 release dates.
  - Chatterbox-Nano training languages.
  - That no F5 small checkpoint was ever released.
  - The top Artificial Analysis "open-weights" entries (Fish Audio Drama 3 Preview, Breeze TTS 2), not checked.
