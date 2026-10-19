# English Pocket TTS candidates — source review, 2026-10-18

No model was downloaded or executed by this reviewer. The research owner subsequently downloaded and exercised the fixed-Alba Soniqo package; this reviewer independently checked its files and raw WAV evidence. Android loading remains a separate gate.

## Soniqo fixed Alba, older English checkpoint

[Immutable publication](https://huggingface.co/soniqo/Pocket-TTS-100M-ONNX-INT8/commit/ad5d3fae3fb2f10024583708597afca8337c094c), dated July 14, 2026; repository revision `ad5d3fae3fb2f10024583708597afca8337c094c`. Use this revision for every file. The following published [manifest](https://huggingface.co/soniqo/Pocket-TTS-100M-ONNX-INT8/blob/main/manifest.json) values were independently matched against the research owner's downloaded files; all ten manifest files, including README, pass size/SHA-256 checks.

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| decoder.int8.onnx | 22695710 | ed8d050fc5da275cfca88224b7d2cc29fde7c23e133618f346e98ac505b3d862 |
| encoder.onnx | 512407 | 2194513df47271ece9f8d2d571facd57b24d96a1d912fdc13bf0e10c69318e85 |
| lm_flow.int8.onnx | 9962530 | 8d627d235c44a597da908e1085ebe241cbbe358964c502c5a5063d18851a5529 |
| lm_main.int8.onnx | 76341079 | bfc0c7e7e3d72864fa3bb2ee499f62f21ddc1474b885f5f3ca570f8be73e787e |
| text_conditioner.onnx | 16388498 | 5217b8474621af91127cfef891714337ae8cba106710ce04a426ec4bd56bbd1e |
| token_scores.json | 123617 | 3baa6ef7d57bac245271e33f96161f3ac60038d753f13f3fdbe24a7d2422ad6b |
| tokenizer.model | 59339 | d461765ae179566678c93091c5fa6f2984c31bbe990bf1aa62d92c64d91bc3f6 |
| vocab.json | 69479 | a2673c232cf49dd6eb1ad850e7c7682f6443c2ab64040d1150e9d8f2a7e3587b |
| LICENSE | 18657 | 9ba9550ad48438d0836ddab3da480b3b69ffa0aac7b7878b5a0039e7ab429411 |

The manifest pins checkpoint `kyutai/pocket-tts-without-voice-cloning@d4fdd22ae8c8e1cb3634e150ebeff1dab2d16df3`, `tts_b6369a24.safetensors`; exporter `csukuangfj/pocket-tts-onnx-export@f075c00bf4bbfbb081a11fd99abbf39df3849e0c`; ONNX opset 14. Voice `alba` is baked into the encoder; this is one voice, with no arbitrary cloning. Output is 24 kHz mono, 1,920 samples per decoder frame. Preserve Kyutai, exporter and Alba MacKenna attribution.

The [historical upstream checkpoint README](https://huggingface.co/kyutai/pocket-tts-without-voice-cloning/blob/d4fdd22ae8c8e1cb3634e150ebeff1dab2d16df3/README.md) itself declares CC BY 4.0. [Kyutai's voice source](https://huggingface.co/kyutai/tts-voices#alba-mackenna) separately declares Alba MacKenna recordings CC BY 4.0. The Soniqo LICENSE is CC BY 4.0; implementation/exporter licenses are separate MIT code licenses. This traces the labels to upstream sources; it does not establish byte identity to an independently regenerated export.

The [pinned exporter](https://raw.githubusercontent.com/csukuangfj/pocket-tts-onnx-export/f075c00bf4bbfbb081a11fd99abbf39df3849e0c/scripts/export_flow_lm.py) uses the legacy main graph inputs `sequence`, `text_embeddings`, followed by flattened state, with conditioning/EOS/state outputs. Actual inspected main/decoder inputs use only FLOAT, INT64 and BOOL, supported by the bundled Sherpa 1.13.8 initial-state allocator; no FLOAT16-state incompatibility remains. The main graph has 20 inputs/outputs and 1,000-slot KV states; the decoder has 57 inputs/outputs. Its wrapper generates latents before decoder PCM callbacks, so the package's native frame-interleaving performance cannot be assumed in this app. Native Pocket ignores its speed argument; use the separately tested shared PCM rate adapter.

Host evidence now confirms compatibility: `research/pocket-en/inspection-result.json` records actual graph signatures and a UNIGRAM/identity/byte-fallback tokenizer, which fits the native frontend rather than Shtorm's BPE workaround. The owner's real Sherpa 1.13.8 run logs `Hello world.` IDs `[2994,578,263]`. Independently parsed all three native WAVs: 24 kHz mono PCM16, 23,040 frames, identical PCM SHA-256 `47b9369b18d3e16a1c2d713fa0cc94d79a5c4fd64ba0ffb67da3ca935586cf62`. Two different dummy reference inputs produce the same fixed-Alba waveform; native speed 1.5 also leaves it unchanged. The raw log/result reports 0.640 s for the first synthesis with two threads, and one 1,920-sample callback before callback-stop. These are owner-run host observations independently checked against artifacts; they are not Android/phone measurements. Stop does not interrupt the preceding native latent generation, so service generation tokens, bounded chunks and deferred unload remain necessary.

## Official Sherpa ready export

The locally preserved native source `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf` has `.github/workflows/export-pocket-tts.yaml`, `.github/scripts/test-offline-tts.sh`, `java-api-examples/run-pocket-tts.sh` and `cxx-api-examples/pocket-tts-en-cxx-api.cc` consuming [sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2). The [HF mirror](https://huggingface.co/csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26/tree/main) revision is `e715955cf50d18d919d37231513c0e914b83661a`.

It supplies the same five filenames plus vocab/scores and three reference WAVs (`bria`, `loona`, `sample_fr_hibiki_crepes`) sourced from Kyutai delayed-streams-modeling audio. Its encoder is approximately 72.7 MB, unlike Soniqo's fixed 512 KB voice adapter. The mirror's actual [LICENSE](https://huggingface.co/csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26/blob/main/LICENSE) says CC BY 4.0 while its [README](https://huggingface.co/csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26) says non-commercial. Thus there is a source-document conflict, not evidence to silently label the package either unrestricted or NC. Reference recordings need their own provenance/terms. No archive/member hash is claimed here because this artifact was not downloaded. This is a viable compatibility fallback, not the first distribution choice.

## Other leads and version labeling

[geneing's Android LiteRT port](https://raw.githubusercontent.com/geneing/LiteRT-Models-Pocket-TTS/main/pockettts/README.md) uses TFLite/LiteRT graphs and Kotlin-managed state with GPU placement; it is not a drop-in five-graph ONNX export. It is a real Android alternative, but adds a different runtime and GPU/device behavior to validate for the CPU-first Huawei/Pixel target. Its own report identifies decoder-transformer GPU quality problems on Mali. Do not copy those device speed claims to LocalVoice.

Official [v3.2 release notes](https://github.com/kyutai-labs/pocket-tts/releases/tag/v3.2.0) introduce default `english_2026-09`; [v3.3.0](https://github.com/kyutai-labs/pocket-tts/releases/tag/v3.3.0) follows it. The July fixed-Alba and January Sherpa packages are older model generations. A working older model is acceptable if labeled accurately; neither is proof of support for the new English checkpoint. Fresh checkpoint/export work is separate from integrating this bounded, already hosted candidate.
