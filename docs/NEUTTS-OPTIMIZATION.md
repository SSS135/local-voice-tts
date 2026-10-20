# NeuTTS Nano on Pixel 7 Pro: optimization plan

Researched 2026-10-20. Goal: sustained RTF ≤ 0.7 over minutes, first audio < 1 s, less memory and heat. The baseline is in `docs/NEUTTS-PHONE-BENCH.md` and `tools/models/neutts/device-results.json`. Runtime facts are in `tools/models/neutts/REPRODUCE.md`.

Link prefixes used below:
- `LC` = https://github.com/ggml-org/llama.cpp/blob/412a363c655b479279d3050d0d1c67a5733697b4 (b11729, our pin)
- `NT` = https://github.com/neuphonic/neutts/blob/ac69851f28fc63a487917e7c2e27f0d75c759cba (our pin, still `main` HEAD)
- `NC` = https://github.com/neuphonic/neucodec/blob/ed3e6cd1bdc374ce14a21355e5eee66a777149ce
- `ORT` = https://github.com/microsoft/onnxruntime/blob/v1.28.2 (commit 33ca9628)

"Local" means we inspected our own artifacts directly: the GGUF header via `gguf.scripts.gguf_dump`, and the ONNX graph via the `onnx` package.

## Where the time goes today

The passage run is 37.8 s of audio in 44.7 s of wall time (RTF 1.18).
- **Generation: about 74% of wall time.** 1,890 codes at about 57 codes/s in the pipeline take about 33 s. The backbone alone reaches 82–92 tok/s, so concurrent decoding costs about 35%.
- **Prefill: about 18%.** 9 segments at 0.70–1.05 s each take about 8 s.
- **NeuCodec decoder** runs overlapped with generation. It needs 148 ms per 0.5 s of audio on 3 threads.

Two facts discovered during this research change the picture.

1. **The output head costs more per token than the transformer itself (local GGUF dump).**
   - The backbone is SmolLM2-shaped: 24 layers, d=576, 9 heads / 3 KV heads, about 116.8M body params, Q4_0 except `ffn_down`, which is Q4_1.
   - The vocabulary is 194,256 tokens: Llama-3 BPE (128k), plus specials, plus 65,536 speech tokens. The embedding is tied and stored in **Q8_0 (111.9M params, 118.9 MB)**.
   - Every generated token multiplies the hidden state against all 194,256 rows. That is 118.9 MB of Q8_0 against 67.7 MB for the whole transformer (Q4_0 47.8 MB + Q4_1 19.9 MB): **64% of weight bytes and 49% of MACs per token.** Only 65,537 rows (speech tokens plus `<|SPEECH_GENERATION_END|>`) can ever be useful.
   - Effective bandwidth is about 90 tok/s × 187 MB ≈ 17 GB/s, so token generation is bandwidth-bound.
2. ~~The real service probably never runs on the X1 cores.~~ **Refuted on the device (2026-10-20):** system_server binds the engine (`c:android`, flags `0x4080001` = AUTO_CREATE | FOREGROUND_SERVICE | SCHEDULE_LIKE_TOP_APP), so `org.localvoice.tts` is in `/top-app` (CPUs 0-7, sched group TOP_APP, procstate 5). This holds with the client app in front, after HOME, and with the screen off (dozing). The `adb shell` CLI with all cores is the realistic setting, and `taskset 3f` below does not apply.

## Status after the 2026-10-20 round

Numbers are in `docs/NEUTTS-PHONE-BENCH.md` ("Optimization round"). Sustained warm RTF is **≈ 1.35–1.42** for the best configuration, so the goal is not met: a warm Pixel 7 Pro caps its BIG cores at ≈ 1.0–1.2 GHz.

| # | Lever | Status |
|---|---|---|
| 1 | Speech-only head | **Done** (`neutts_jni.cpp`). Pooled LAST embeddings plus one ggml `mul_mat` over a row view of llama's own repacked tied head (rows 128256–193799 cover END 128261 and speech 128262–193797). It needs no llama.cpp patch and no extra memory; it includes `llama-model.h` from the pinned source for `model->output`. Logits are bit-identical to the full head (CLI `head`: max \|Δ\| 0 over 3 seeds × 150 steps; the full-vocabulary top-50 never held another token, so sampling is unchanged). Backbone tok/s ×1.35 (wireless charger, status 2) and ×1.43 (warm, wired). There is no memory saving: the repacked 119 MB head is reused, not removed. Removing it needs a llama.cpp patch. |
| 2 | Decode chunk 50 / lookback 50 | **Done as config** (`streamChunk`, `streamLookback`; the catalog sets 50/50, the backend default stays upstream 25/50). Decode has almost no fixed cost (kernels are 191 of 197 ms at F=81), so frames per audio-second fall 164 → 107. Host check against a one-shot decode of the 6 voice references: log-mel L1 0.294 (upstream 25/50), 0.319 (50/50), 0.353 (50/25). Quality is **provisional** until the `docs/TTS-QUANT-QUALITY.md` gate passes. ORT spinning was 2× slower (rejected). `use_device_allocator_for_initializers` changed nothing (rejected). Profile at F=81: DynamicQuantizeMatMul 42%, fp32 Conv 33%, ConvTranspose 4%. |
| 3 | Longer NeuTTS segments | **Done as config** (`segmentLimit`, catalog 250; jo worst case ≈ 1,910 of 2,048 tokens). The passage takes 6 prefills instead of 9. Reference trimming was not done (it needs a speaker-similarity check). |
| 4 | Two-sequence batching | **Built, parked** (branch `experiment/neutts-batching`, config `batchSegments`, default off). No end-to-end gain on a warm phone (ABAB RTF 1.444 unbatched vs 1.453 batched). A pair step costs 1.6–1.75× a single-token step. That is intrinsic, not a defect of our path; the per-op profile is in `docs/NEUTTS-PHONE-BENCH.md` ("Two-sequence batching"). |
| 5 | Placement/ADPF | Pinning premise refuted (see fact 2). **ADPF tried and removed**: a hint session over all synthesis threads (deadline 0.7 × audio, power-efficient), ABBA on a warm phone. No cooling, and RTF was unchanged for NeuTTS (1.49 on vs 1.52 off) and Kokoro (1.57 vs 1.46). Both voices run at RTF > 1 warm, so hints can only ask for boost. Details: `docs/NEUTTS-PHONE-BENCH.md` ("ADPF performance hints"). |
| 6 | Decoder memory | `use_device_allocator_for_initializers` gave no RSS change. Model surgery (int8 convs) not done. |
| 7 | Pure Q4_0 (`ffn_down` Q4_1 → Q4_0, from the Q8_0 GGUF, `--pure --token-embedding-type q8_0`) | **No speed gain** (warm-ish interleaved: 102/88.6/85.5 vs 95.1/85.8/85.6 tok/s); RSS +2.5 MB. Not adopted. `ffn_down` is 19.9 of ≈ 108 MB per token after item 1, and the generic Q4_1 dotprod path is also bandwidth-bound. A repacked Q4_1 kernel can therefore save at most a few %. |

Still open to close the warm gap (prefill ≈ 20%, generation 48 codes/s against the 50 needed, decode ≈ 30 s per 37 s overlapped):
- two-sequence batching (item 4);
- shorter references (prefill and depth);
- int8 decoder convs, which cut about a third of decode time;
- chunk 50 / lookback 25 if the quality gate allows it;
- duty-cycling to reduce heat (ADPF alone did not help; see item 5).

## Ranked recommendations

| # | Change | Expected effect (basis) | Effort | Risk |
|---|---|---|---|---|
| 1 | **Speech-only LM head.** Skip llama.cpp's 194k-row head, multiply the final hidden state by a 65,537-row slice, and sample over that slice. Keep `token_embd` in the mmapped CPU buffer. | Token generation ×1.3–1.6 (bytes per token 187 → 108 MB with a Q8_0 slice, or 89 MB with Q4_0; MACs 229M → 155M). About −119 MB anonymous memory. Sampling works on 65k logits instead of 194k. | M (2–3 days) | Low: speech-row logits are bit-identical. Sampling renormalizes over speech tokens only; today we just skip non-speech tokens. |
| 2 | **Cheaper streaming decode.** Keep the first chunk at 25 codes, then use 50-code chunks with 25–50 lookback. Tune ORT spinning. Profile first. | Decoder CPU about −50% (81 frames per 1 s instead of per 0.5 s: 0.30 → 0.15 decode-RTF on 3 threads). Less contention moves pipeline generation from 57 toward the 82+ tok/s standalone figure. | S (≤1 day) | Low–medium: chunk-boundary artifacts, because the decoder's attention is bidirectional. Gate on SNR against a one-shot decode. |
| 3 | **Fewer, longer segments for NeuTTS, plus shorter references.** Use a per-backend `SpeechPlan` limit of about 250–300 chars after the first segment, within the 2048-token budget. Use references of ≤ 5 s (≈250 codes). | Prefill share 0.21 → about 0.08 RTF (9 → about 4–5 prefills, about 120 fewer tokens each). Lower depth also speeds generation. | S | Low–medium: longer Stop latency (but `cancel` aborts `llama_decode`), the depth penalty, and reference-quality trade-offs. |
| 4 | **Two-sequence batched generation.** Generate segment k+1 alongside segment k in one `llama_decode` (two `seq_id`s), and run the next prefill in the same batch. | ×1.5–1.8 generation throughput if a batch of 2 costs ≤ 1.3× a single token (bandwidth-bound; confirm with `llama-batched-bench`). Gives margin under the A78-only cpuset and when throttling. | M–L (4–6 days) | Low for quality, medium for complexity: per-sequence samplers and KV, and audio ordering. |
| 5 | **Placement, polling and ADPF.** Pin generation to the two A78s (`foreground` cpuset) and the decoder to the A55s. Set ggml `poll` to 0–10. Add an ADPF hint session with target = chunk audio × 0.7 and `setPreferPowerEfficiency`. Pause generation when headroom is high or buffered audio exceeds N s. | Sustains the gains from 1–4 for minutes; less heat. Unquantified: Google publishes no ADPF numbers. | S–M | Medium: per-device core layout. Google advises against hard affinity. |
| 6 | **Decoder memory.** Set ORT `use_device_allocator_for_initializers=1`, mmap external data or prepacked weights, int8-quantize the 100 MB of fp32 convs, and trim `window_sum`. | −100 to −200 MB heap and a lower load-time peak. Quantized convs are probably also faster. | M | Medium: requires re-exporting the decoder and a quality check. |
| 7 | **Backbone requant variants.** Use pure Q4_0 (`ffn_down` Q4_1 → Q4_0 from the Q8_0 GGUF), and try a Q4_0 head slice. | +5–10% generation (Q4_1 has no repack layout). | S to test | Low–medium for quality. Run the WER check. |

Rejected (evidence below):
- reordering the template or reusing KV across segments;
- speculative decoding;
- KleidiAI;
- Q4_K, IQ4_NL or Q8_0 backbones;
- KV-cache quantization;
- flash attention (re-test only at depth > 1024);
- every GPU/NPU path except an optional LiteRT-GPU decoder spike;
- ExecuTorch, MLC and MNN ports.

**Projection** (arithmetic from measured parts):
- **Items 1+2+3 together:**
  - generation 50/(57×1.4 → about 90–100 with less contention) ≈ 0.5–0.55;
  - prefill ≈ 0.08;
  - tail and misc ≈ 0.03.
  - That gives **RTF ≈ 0.6–0.67** on all cores. It may be about 0.7–0.75 in the real A78-only cpuset (unmeasured).
- **Item 4** brings generation to ≈ 0.35 and **RTF ≈ 0.45–0.5**, which leaves room for throttling.
- **First audio:**
  - prefill: ref-phoneme prefix warmed at load, ≈ 280–400 new tokens at about 560 tok/s ≈ 0.5–0.7 s;
  - 30 codes at about 120 tok/s (4 threads, decoder idle, item 1) ≈ 0.25 s;
  - 31-frame decode ≈ 0.09 s.
  - Total **≈ 0.85–1.05 s**. A 15+5 first chunk saves a further ≈ 0.1 s.

## First three steps

The harness is `tools/models/neutts/build-cli.ps1` with `cli/NeuTtsCli.java`, invoked as `say m <voice> <file> out.wav g/b/d` and `gen m <n> g/b`. Run unpinned (the service is in `top-app`, all cores; see fact 2). Also run the official b11729 Android binaries from the bench.

1. **Measure what decides the order (½–1 day, no app changes).**
   - Run `llama-batched-bench -m nano-Q4_0.gguf -c 4096 -b 512 -ub 512 -npp 600 -ntg 128 -npl 1,2,3,4 -t 2 -C 0x30 --cpu-strict 1`, then repeat with `-t 4 -C 0xF0` and with `-C 0xC0`. This gives the batch-2 cost behind item 4 and the gap between A78-only and X1 throughput.
   - Build a timing-proxy GGUF with Python `gguf`: vocabulary and `token_embd` cut to 65,600 rows. Then run `llama-bench -p 512 -n 64 -d 512 -t 2` on the original and the proxy. This gives the item 1 gain. If the proxy fails to load because of special-token ids, go straight to step 2.
   - Check the load log for the `CPU_REPACK model buffer size`. About 160 MiB means the Q8_0 head is duplicated in anonymous memory.
   - In the app, run `cat /proc/$(pidof <service process>)/cpuset` during `speak()` from a reader app, with the screen on and off.
   - Enable ORT `EnableProfiling` in a debug build of `openDecoder` and run F = 1/31/81. Compare total op time against wall time to get the fixed per-Run cost, and get the shares of the fp32 `Conv` and `DynamicQuantizeMatMul` ops.
2. **Implement item 1, then measure.**
   - Context with `embeddings=true` and `pooling_type=LAST`, which makes the graph skip the head.
   - Run our own ggml `mul_mat` of `llama_get_embeddings_seq` against a 65,537-row Q8_0 slice in a repack buffer, on the generation threadpool.
   - Run the llama sampler chain via `llama_sampler_apply` on a custom `llama_token_data_array`.
   - Set `tensor_buft_overrides` so `token_embd.weight` goes to the CPU buffer.
   - Acceptance:
     - A new CLI mode compares slice logits against full logits on a fixed prompt (max |Δ| < 1e-4).
     - `gen m 200 2/4` throughput rises by ≥ 1.3×.
     - `say` RTF no worse; quality per the gate in `docs/TTS-QUANT-QUALITY.md`.
     - `dumpsys meminfo` shows the native heap drop.
3. **Implement items 2 and 3, then run a sustained test.**
   - Use `NeuTtsStream` CHUNK 50 after the first window, LOOKBACK 25 or 50 (A/B both), and a NeuTTS-specific `SpeechPlan` limit computed from the context budget. Set ORT `allow_spinning=1`, `spin_duration_us=1000` and `force_spinning_stop=1` if profiling shows the fixed cost is wakeups.
   - Quality: decode each segment's codes once in full and compare against the streamed output (SNR and log-mel L1, with the seed fixed by the CLI). Then run the WER check.
   - Sustained: concatenate `passage.txt` 8 times (about 5 min) and run `say` unpinned. Log `dumpsys thermalservice` and the cpufreq of policy4/policy6 every 10 s. Report RTF per minute, stalls, first audio and peak BIG temperature.

## Evidence per item

### 1. Speech-only LM head
- Tied head: when `output.weight` is absent, llama loads `token_embd` again as `output` with `TENSOR_DUPLICATED` ([LC/src/models/llama.cpp#L41-L45](https://github.com/ggml-org/llama.cpp/blob/412a363c655b479279d3050d0d1c67a5733697b4/src/models/llama.cpp#L41-L45); loader [LC/src/llama-model-loader.cpp#L1163-L1169](https://github.com/ggml-org/llama.cpp/blob/412a363c655b479279d3050d0d1c67a5733697b4/src/llama-model-loader.cpp#L1163-L1169)). It is placed in the output layer's buffer list, and Q8_0 has a dotprod `q8_0_4x4` repack ([LC/ggml/src/ggml-cpu/repack.cpp#L4974-L5121](https://github.com/ggml-org/llama.cpp/blob/412a363c655b479279d3050d0d1c67a5733697b4/ggml/src/ggml-cpu/repack.cpp#L4974-L5121)). The head is therefore probably held twice: once mmapped (for `get_rows`) and once as a 119 MB anonymous repacked copy. This fits the 476 MB native heap; confirm with the load log.
- The head shape is hard-checked as `{n_embd, n_vocab}`, so dropping rows from the GGUF fails. No built-in vocabulary restriction exists.
- No-patch path: with `cparams.embeddings` and a pooling type other than NONE, `build_logits` drops `t_logits`, so the head is never computed ([LC/src/llama-graph.cpp#L4113-L4124](https://github.com/ggml-org/llama.cpp/blob/412a363c655b479279d3050d0d1c67a5733697b4/src/llama-graph.cpp#L4113-L4124)). `t_embd` is the post-`output_norm` state ([LC/src/models/llama.cpp#L228-L235](https://github.com/ggml-org/llama.cpp/blob/412a363c655b479279d3050d0d1c67a5733697b4/src/models/llama.cpp#L228-L235)).
- The KV-cached incremental use of LAST pooling has not been exercised upstream, so check it against full logits in step 2.
- Alternative: a roughly 30-line patch to our vendored copy that makes `output` a row view and n_vocab-aware logits. Row offsets must be multiples of 4 for the repack.
- `tensor_buft_overrides` and `use_extra_bufts` are in `llama_model_params` ([LC/include/llama.h#L325](https://github.com/ggml-org/llama.cpp/blob/412a363c655b479279d3050d0d1c67a5733697b4/include/llama.h#L325), #L353).
- Speech ids are 128262–193797 and the end token is 128261 (GGUF EOS), directly before them (checked on the device; the earlier "vocab − 65,536" guess was wrong).
- No prior art was found for vocabulary trimming in llama.cpp-based TTS (OuteTTS, Orpheus, TTS.cpp).

### 2. Decoder streaming cost
- Decoder architecture: FSQ → `fc_post_a` 2048→1024 → Conv1d k7 → 2 ResNet blocks → 12 transformer blocks (d1024, 16 heads) → 2 ResNet blocks → ISTFT (n_fft 1920, hop 480) ([NC/neucodec/codec_decoder_vocos.py#L263-L366](https://github.com/neuphonic/neucodec/blob/ed3e6cd1bdc374ce14a21355e5eee66a777149ce/neucodec/codec_decoder_vocos.py#L263-L366)).
- Attention is unmasked and bidirectional (`attn_mask=None, is_causal=False`, [NC/neucodec/bs_roformer5.py#L76-L77](https://github.com/neuphonic/neucodec/blob/ed3e6cd1bdc374ce14a21355e5eee66a777149ce/neucodec/bs_roformer5.py#L76-L77)). Lookback therefore buys boundary consistency, not receptive field. The local conv and ISTFT context is about ±13 frames (derived).
- There is no upstream guidance on lookback. 25/5/50/1 are bare constants ([NT/neutts/neutts.py#L141-L147](https://github.com/neuphonic/neutts/blob/ac69851f28fc63a487917e7c2e27f0d75c759cba/neutts/neutts.py#L141-L147)), unchanged on newer branches.
- There is no lite, causal or streaming NeuCodec decoder. `distill-neucodec` distills the encoder only ([HF card @6f2b93b3](https://huggingface.co/neuphonic/distill-neucodec/blob/6f2b93b3fa2bc74740ba1bd10622e1ee37dbbe46/README.md)). neucodec #20 says "no specific plans".
- Cost model from our measurements: ≈ 47 ms fixed + 1.24 ms/frame.

  | Scheme | Frames per decode | Cost |
  |---|---|---|
  | Today (25 + 5 + 50 + 1) | 81 per 0.5 s | 296 ms per audio-second |
  | Chunk 50, lookback 25 | 81 per 1 s | 148 ms per audio-second |
  | Chunk 50, lookback 50 | 106 per 1 s | ≈ 178 ms per audio-second |

- Local ONNX graph (`neucodec-decoder-int8.onnx`):
  - 51 `DynamicQuantizeLinear`+`MatMulInteger` (int8 weights, 155 MB).
  - **9 fp32 Conv weights totalling 100 MB** (embed 1024×1024×7, 8 ResNet 1024×1024×3).
  - `istft.inverse_basis` 14.8 MB (ConvTranspose).
  - `window_sum` 11.5 MB, sized for 6,003 frames.
  - The fp32 convs are about 19% of MACs (≈ 36M of ≈ 190M per frame) but run without int8 dotprod, so they may dominate the decode time. Profile to confirm.
- ORT internals:
  - At `ORT_ENABLE_ALL`, `DynamicQuantizeLinear`+`MatMulInteger` fuse into `DynamicQuantizeMatMul` ([ORT/onnxruntime/core/optimizer/graph_transformer_utils.cc#L399-L400](https://github.com/microsoft/onnxruntime/blob/v1.28.2/onnxruntime/core/optimizer/graph_transformer_utils.cc#L399-L400)).
  - On G2 these run as U8S8 `udot` ([ORT/onnxruntime/core/mlas/lib/platform.cpp#L695-L705](https://github.com/microsoft/onnxruntime/blob/v1.28.2/onnxruntime/core/mlas/lib/platform.cpp#L695-L705)). KleidiAI overrides need SME ([#L706-L724](https://github.com/microsoft/onnxruntime/blob/v1.28.2/onnxruntime/core/mlas/lib/platform.cpp#L706-L724)).
  - There is no fp16 MatMul in the CPU EP.
  - XNNPACK EP has no quantized MatMul, so there is no gain for this model.
- Per-Run fixed cost: we set `allow_spinning=0`, so every parallel op wakes workers through futex.
  - Middle ground: `session.intra_op.allow_spinning=1` + `session.intra_op.spin_duration_us` + `session.force_spinning_stop=1` ([ORT/include/onnxruntime/core/session/onnxruntime_session_options_config_keys.h](https://github.com/microsoft/onnxruntime/blob/v1.28.2/include/onnxruntime/core/session/onnxruntime_session_options_config_keys.h) L179-195, L289).
  - Diagnose with `EnableProfiling`.

### 3. Prompt structure, segment length, reference length
- The template puts reference codes after the target text ([NT/neutts/neutts.py#L504-L529](https://github.com/neuphonic/neutts/blob/ac69851f28fc63a487917e7c2e27f0d75c759cba/neutts/neutts.py#L504-L529)), and training used the same single-utterance layout `…{phones}<|TEXT_PROMPT_END|>\nassistant:<|SPEECH_GENERATION_START|>{codes}<|SPEECH_GENERATION_END|>` ([NT/examples/finetune.py#L72-L73](https://github.com/neuphonic/neutts/blob/ac69851f28fc63a487917e7c2e27f0d75c759cba/examples/finetune.py#L72-L73), max_seq_len 2048).
- Cloning is Llasa-style continuation ([NT/TRAINING.md](https://github.com/neuphonic/neutts/blob/ac69851f28fc63a487917e7c2e27f0d75c759cba/TRAINING.md)). Moving the codes before the target text, or a multi-turn "speaker turn", is out of distribution.
- Issue [#71](https://github.com/neuphonic/neutts/issues/71) reports that feeding the previous sentence's codes "does not seem to work". Exact KV reuse beyond the reference-phoneme prefix (which we already do) is impossible because every later token attends to the target text. **Rejected.**
- Length guidance:
  - "2048 tokens … ~30 seconds of audio (including prompt duration)" ([NT/README.md#L40](https://github.com/neuphonic/neutts/blob/ac69851f28fc63a487917e7c2e27f0d75c759cba/README.md#L40)).
  - A maintainer says to generate "one sentence or paragraph at a time" ([#15](https://github.com/neuphonic/neutts/issues/15)).
  - References should be 3–15 s ([NT/README.md#L294-L303](https://github.com/neuphonic/neutts/blob/ac69851f28fc63a487917e7c2e27f0d75c759cba/README.md#L294-L303)).
- Budget per segment: 2048 − 164 (prefix with reference phonemes) − reference codes (dave 372, jo 653, sophie 175) − margin.
  - The passage averages about 2.4 codes/char plus about 0.5–0.9 phoneme tokens/char, so about 3.3 tokens per char.
  - Dave allows about 440 chars; jo about 350.
  - A limit of 250–300 chars (≈15–18 s) fits every bundled voice.
- `SpeechPlan.LIMIT` is 140 today and shared by all backends.
- Depth cost: 1-thread token generation fell from 95 tok/s at depth 0 to 70 at depth 512 (bench). Longer segments raise the average depth by about 300 tokens, which costs about 5–10% of generation speed. That partly offsets the prefill saving, and shorter references recover it.
- Context shifting and state save/restore do not apply (segments < 2048, no reusable state). `seq_cp` within a unified KV is metadata-only ([LC/src/llama-kv-cache.cpp#L473-L560](https://github.com/ggml-org/llama.cpp/blob/412a363c655b479279d3050d0d1c67a5733697b4/src/llama-kv-cache.cpp#L473-L560)); this matters for item 4 (shared reference-phoneme prefix across two sequences).

### 4. Batched generation
- Token generation is bandwidth- and overhead-bound:
  - 1 → 2 → 4 threads gives only 70 → 84.5 → 92 tok/s (bench);
  - 17 GB/s effective;
  - pp512 reaches 725 tok/s, about 8× the token-generation rate.
- A second sequence in the same `llama_decode` reuses each weight read, so a step with 2 tokens should cost well under 2×. Measure with `llama-batched-bench` ([LC/tools/batched-bench/README.md](https://github.com/ggml-org/llama.cpp/blob/412a363c655b479279d3050d0d1c67a5733697b4/tools/batched-bench/README.md)) before building.
- Implementation:
  - `n_seq_max=2`, `kv_unified=true`, `n_ctx` 4096 (KV f16 ≈ 18 KB/token → 75 MB);
  - one sampler per sequence;
  - next segment's prefill tokens submitted in the same batch as current-segment tokens;
  - first segment kept solo for latency.

### 5. Threads, placement, thermal
- Topology: cpu0-3 A55 at 1.80 GHz, cpu4-5 A78 at 2.35 GHz, cpu6-7 X1 at 2.85 GHz ([cheetah power_profile.xml](https://android.googlesource.com/device/google/pantah/+/refs/heads/main/cheetah/overlay/frameworks/base/core/res/res/xml/power_profile.xml); policies 0/4/6 in `init.gs201.rc`).
- cpusets: `foreground` 0-5 and `background` 0-3 ([init.gs201.rc](https://android.googlesource.com/device/google/gs201/+/refs/heads/main/conf/init.gs201.rc); mapping in [task_profiles.json](https://android.googlesource.com/platform/system/core/+/refs/heads/main/libprocessgroup/profiles/task_profiles.json)). `sched_setaffinity` is intersected with the cpuset.
- ggml threadpool:
  - Fields are `cpumask`, `strict_cpu`, `prio` and `poll` ([LC/ggml/include/ggml.h#L3009-L3016](https://github.com/ggml-org/llama.cpp/blob/412a363c655b479279d3050d0d1c67a5733697b4/ggml/include/ggml.h#L3009-L3016)).
  - The default `poll=50` spins 128K×50 relax iterations before sleeping ([LC/ggml/src/ggml-cpu/ggml-cpu.c#L3208-L3220](https://github.com/ggml-org/llama.cpp/blob/412a363c655b479279d3050d0d1c67a5733697b4/ggml/src/ggml-cpu/ggml-cpu.c#L3208-L3220)). It competes with the decoder for cores.
  - With `strict_cpu`, the calling thread is also pinned permanently, so the JNI synthesis thread inherits worker 0's mask.
- ORT's own affinity is compiled out on Android ([ORT/onnxruntime/core/platform/posix/env.cc#L207](https://github.com/microsoft/onnxruntime/blob/v1.28.2/onnxruntime/core/platform/posix/env.cc#L207)). Use `SetGlobalCustomCreateThreadFn` to record tids and set affinity, or include the tids in the ADPF session.
- ADPF:
  - `PerformanceHintManager.createHintSession(tids, target)` (API 31), `setPreferPowerEfficiency` (API 35), NDK `APerformanceHint_*` (API 33+) ([docs](https://developer.android.com/reference/android/os/PerformanceHintManager.Session)).
  - Hints are honoured only while the uid is ≤ IMPORTANT_FOREGROUND ([HintManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/power/hint/HintManagerService.java)).
  - Google gives no quantified benefit and advises against hard affinity ([source.android.com perf-hint](https://source.android.com/docs/core/perf/performance-hint-api)).
- Thermal: `PowerManager.getThermalHeadroom(forecastSeconds)` (API 30; at most about 1 call/s) and `addThermalStatusListener` (API 29) ([docs](https://developer.android.com/reference/android/os/PowerManager)).
- Duty-cycling: once RTF < 0.7, stop generating when more than about 10 s of audio is buffered and resume below 5 s. That gives cores idle time instead of running at a constant medium load.
- Flash attention: CPU split-KV only applies at KV ≥ 512 ([LC/ggml/src/ggml-cpu/ops.cpp#L9578-L9611](https://github.com/ggml-org/llama.cpp/blob/412a363c655b479279d3050d0d1c67a5733697b4/ggml/src/ggml-cpu/ops.cpp#L9578-L9611)). Below that it parallelizes over 9 heads only, which explains the 20% loss. Re-test once segments reach depth > 1024.

### 6. Memory
- Today: PSS 720 MB, native heap 476 MB.
  - Likely llama.cpp share: repacked Q4_0 47.8 MB + repacked Q8_0 head 118.9 MB + KV 2048 × 18 KB = 38 MB + compute buffers.
  - The rest is ORT.
- ORT keeps both packed and freed copies:
  - `MatMulIntegerBase::PrePack` allocates packed B ([ORT/onnxruntime/core/providers/cpu/quantization/matmul_integer_base.h#L25-L80](https://github.com/microsoft/onnxruntime/blob/v1.28.2/onnxruntime/core/providers/cpu/quantization/matmul_integer_base.h#L25-L80)).
  - The original is erased after packing ([session_state.cc#L667-L673](https://github.com/microsoft/onnxruntime/blob/v1.28.2/onnxruntime/core/framework/session_state.cc#L667-L673)), but initializers come from the BFC arena, which does not return memory to the OS.
- Keys (exact strings, in the config-keys header):
  - `session.use_device_allocator_for_initializers=1`
  - `session.save_external_prepacked_constant_initializers`: prepacked weights are mmapped on later loads; generate the file on the device.
  - `session.use_memory_mapped_ort_model` + `session.use_ort_model_bytes_for_initializers`, for a `.ort` model
  - `session.disable_prepacking`: keeps the weights file-backed but packs inside every GEMM, so it is slower.
- External-data initializers are mmapped ([tensorprotoutils.cc#L1637-L1665](https://github.com/microsoft/onnxruntime/blob/v1.28.2/onnxruntime/core/framework/tensorprotoutils.cc#L1637-L1665)).
- Model surgery:
  - int8 static-QDQ the 9 fp32 convs: −75 MB, probably faster too;
  - truncate `window_sum` to the largest window (≤ 200 frames): −11.4 MB.
- Item 1 removes the 119 MB repacked head and replaces it with a 40 MB slice (or 21 MB in Q4_0).

### 7. Backbone quantization and kernels
- Repack is on by default:
  - `GGML_CPU_REPACK` defaults to ON ([LC/ggml/CMakeLists.txt#L152](https://github.com/ggml-org/llama.cpp/blob/412a363c655b479279d3050d0d1c67a5733697b4/ggml/CMakeLists.txt#L152));
  - `use_extra_bufts=true` ([LC/src/llama-model.cpp#L3362](https://github.com/ggml-org/llama.cpp/blob/412a363c655b479279d3050d0d1c67a5733697b4/src/llama-model.cpp#L3362)).
  - On dotprod without i8mm, Q4_0 → `q4_0_4x4` for both gemm and gemv. Q8_0 and IQ4_NL get 4x4 layouts and Q4_K gets 8x4. **Q4_1 has none.** Our 24 `ffn_down` tensors (Q4_1, 19.9 MB) therefore run the generic path.
  - Requantize with `llama-quantize --allow-requantize --pure` from the published Q8_0 GGUF ([HF @16705743](https://huggingface.co/neuphonic/neutts-nano-q8-gguf)) and A/B it.
- Q4_K: dotprod kernels gave 0.97–0.99× token generation on a Pi 5 ([PR #17494](https://github.com/ggml-org/llama.cpp/pull/17494)). Q8_0 moves 1.9× the bytes. IQ4_NL adds a lookup over Q4_0. **Keep Q4_0.**
- KleidiAI: off by default and fetched at configure time ([LC/ggml/CMakeLists.txt#L153](https://github.com/ggml-org/llama.cpp/blob/412a363c655b479279d3050d0d1c67a5733697b4/ggml/CMakeLists.txt#L153)). Its dotprod Q4_0 kernels measured +19% tg at 1 thread on a Pixel 8 but −20% on a Galaxy A34 ([PR #11390](https://github.com/ggml-org/llama.cpp/pull/11390)). Low value; skip.
- Speculative decoding: no Nano-compatible draft model exists. `neutts-nano-bpe-bilingual-pruned-n-1000` uses a different tokenizer and input. N-gram drafting has no q distribution ([LC/common/speculative.cpp#L368](https://github.com/ggml-org/llama.cpp/blob/412a363c655b479279d3050d0d1c67a5733697b4/common/speculative.cpp#L368)). At temperature 1 / top-k 50, acceptance ≈ p(token), which is ≪ 0.3 for speech codes. **Rejected.**
- KV q8_0: needs flash attention and loses the split-KV path. At depth < 2048 the KV is ~38 MB. **Rejected.**

## GPU / NPU / alternative runtimes (Tensor G2)

| Path | Works on G2? | Evidence | Expected gain | Verdict |
|---|---|---|---|---|
| llama.cpp Vulkan (Mali-G710) | Runs | Our bench: tg 51 vs CPU 92, pp 176 vs 725. Driver reports `int dot 0`, `matrix cores none`. tg is bandwidth-bound and Mali copy bandwidth is ≈19 GB/s on G715 ([PR #15800 comment](https://github.com/ggml-org/llama.cpp/pull/15800)), about what the CPU already gets. Open bug [#28637](https://github.com/ggml-org/llama.cpp/issues/28637): matmul mis-tiles at subgroup size 16 (Mali), fix in PR #30106 (unmerged), so our Vulkan output may also have been wrong. No Mali tuning merged by b11729. | Negative | Drop |
| llama.cpp OpenCL | No | Rejects non-Adreno/Intel devices in code ([LC/ggml/src/ggml-opencl/ggml-opencl.cpp#L6801-L6824](https://github.com/ggml-org/llama.cpp/blob/412a363c655b479279d3050d0d1c67a5733697b4/ggml/src/ggml-opencl/ggml-opencl.cpp#L6801-L6824)) | — | Drop |
| MLC-LLM / TVM OpenCL | Probably | Pixel 8 Pro RedPajama-3B 2.5 tok/s (mlc-llm #1495). No small-model Mali numbers. Same bandwidth ceiling. | Likely negative | Drop |
| ExecuTorch XNNPACK / Vulkan | Yes | Pixel 9 Pro XL (G4, Mali-G715), Qwen3-0.6B decode: llama.cpp CPU 46.5, ET XNNPACK 42, ET Vulkan 21 tok/s ([arXiv 2605.08195 Table 5](https://arxiv.org/html/2605.08195)) | ≈1× CPU, 0.5× GPU | Drop |
| LiteRT GPU (ML Drift, OpenCL via `libOpenCL-pixel.so`) for the decoder | Yes | Mimi decoder convs 114 ms GPU vs 936 ms fp32 CPU on Pixel 8a, but the transformer had to stay on CPU (fp16 loss, ≈12 dB SNR) ([HF Mimi-LiteRT](https://huggingface.co/litert-community/Mimi)). Needs static shapes and op rewrites. No FFT kernel, so ISTFT stays on CPU. | Offloads the fp32 conv/ISTFT share (and possibly the transformer) of decoding, which is ≤ 9–15% CPU duty after item 2 | Optional spike after items 1–3. Gate on SNR. |
| ORT NNAPI EP | Deprecated (Android 15) but the darwinn TPU HAL is present on gs201 | EP lacks MatMulInteger, DynamicQuantizeLinear, LayerNorm, 1-D Conv and ConvTranspose (ORT NNAPI op list) | ≈0 (graph fragments) | Drop |
| Tensor TPU via LiteRT NPU / Tensor SDK | No | Tensor SDK beta lists **G5/G6 only** ([developers.google.com/edge/litert/next/tensor-sdk](https://developers.google.com/edge/litert/next/tensor-sdk)) | — | Not available |
| AICore / ML Kit GenAI | No | Gemini Nano only, Pixel 9+ ([ML Kit GenAI](https://developers.google.com/ml-kit/genai)) | — | Not available |
| MNN CPU | Runs (3.6.1; export needs a rope-scaling fix) | Measured warm and interleaved against llama.cpp ([NEUTTS-MNN.md](NEUTTS-MNN.md)). Decode 0.75× with 4 threads and the full head (45 vs 60 tok/s), 0.77× with the speech head (63 vs 82), 0.84× with 2 A78 threads (59 vs 70). Prefill 0.68–0.88×. The 4-bit export has 3× the logit KL of Q4_0. The authors' 1.66–1.85× ([arXiv 2506.19884](https://arxiv.org/html/2506.19884)) does not carry over to G2. | Negative | Drop |

Thermal: for memory-bound decode, DRAM energy is the same whichever engine runs it. Measurements on a Pixel 7 showed GPU llama.cpp spending more energy per token than CPU, with CPU and GPU governors pulling each other's clocks down ([arXiv 2507.02135](https://arxiv.org/html/2507.02135v1)). Offloading does not reduce heat for the backbone.

## Open questions

1. Which cpuset and process state does the service get in real use: a reader app in the foreground, screen off, TalkBack? This decides whether to tune for X1 or for A78-only.
2. Does the load log confirm the 119 MB repacked head duplicate? What is the actual llama.cpp versus ORT split of the 476 MB?
3. Is `embeddings=true` + LAST pooling exact with KV-cached single-token steps at b11729, or should we patch the head view directly?
4. Is a lookback of 25 audibly worse than 50 at 50-code chunks (SNR and listening)?
5. Is a ≤ 5 s reference acceptable for voice similarity on the bundled voices? Should jo (653 codes, 13 s) be trimmed?
6. Is the NeuTTS-specific segment limit (250–300 chars) acceptable for Stop latency? `cancel` aborts within a token, so the risk is mainly prosody across longer segments, which may improve.
7. Should `speak()` duty-cycle through `SynthesisCallback` back-pressure, or should the engine add its own buffer-ahead cap?
