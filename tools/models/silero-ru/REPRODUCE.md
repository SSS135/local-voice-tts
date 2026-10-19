# Silero v5_6_ru: export and validation

Produces the files pinned in `runtime-manifest.json` (catalog `silero-ru`, release `silero-ru-model-v1`). Re-export is byte-identical with `requirements-export.txt`.

## Runtime decision

- The package is a `torch.package` (`v5_6_ru.pt`): TorchScript FastPitch-style acoustic model + Vocos-style vocoder (48 kHz native; 24/8 kHz are PQMF bands of it), Python text frontend, n-gram stress/ё accentor and a 3-layer BERT homograph solver.
- Runtime: the sherpa-onnx 1.13.8 AAR's existing **VITS** path, no new native library. `export_onnx.py` builds one ONNX graph with the VITS inputs `x, x_length, noise_scale, length_scale, noise_scale_w, sid, emotion_id` and metadata `frontend=characters`, `bos_id`/`eos_id`; `sid` = speaker, `emotion_id` = Silero sentence type, `length_scale` = 1/speed (native durations).
- Rejected: `onnxruntime-android` beside sherpa. Only one `libonnxruntime.so` can ship; sherpa's needs symbol version `VERS_1.28.2`, `libonnxruntime4j_jni.so` from Maven 1.28.0 (no 1.28.2 published) needs `VERS_1.28.0`, so either choice fails symbol lookup in bionic. PyTorch Mobile/ExecuTorch unnecessary.
- The scripted modules cannot be traced or exported as-is (package-loaded `RecursiveScriptModule`s; scripted `MultiheadAttention` fast-path branches break the TorchScript exporter), so the graph is an eager functional mirror with the same weights. `irfft` is two matmuls with a precomputed basis (+23 MB).
- Stress frontend: `export_frontend.py` writes `frontend.bin` (tensors) and `frontend.json` (n-grams, exceptions, homograph dictionary, Java-compatible phrase regexes, WordPiece vocabulary) for `app/.../backend/SileroStress.java`.

## Steps (PowerShell, repo root)

```powershell
.\tools\models\silero-ru\download-inputs.ps1          # -> tools\models\silero-ru\build\v5_6_ru.pt (pinned SHA-256)
# uv project with requirements-export.txt (torch from https://download.pytorch.org/whl/cpu)
uv run python tools\models\silero-ru\export_onnx.py tools\models\silero-ru\build\v5_6_ru.pt tools\models\silero-ru\build\out
uv run python tools\models\silero-ru\export_frontend.py tools\models\silero-ru\build\v5_6_ru.pt tools\models\silero-ru\build\out tools\models\silero-ru\frontend-parity.json
uv run python tools\models\silero-ru\verify_onnx.py tools\models\silero-ru\build\v5_6_ru.pt tools\models\silero-ru\build\out
uv run python tools\models\silero-ru\validate_host.py tools\models\silero-ru\build\v5_6_ru.pt tools\models\silero-ru\build\out <wav_dir>
Get-FileHash tools\models\silero-ru\build\out\*       # compare with runtime-manifest.json
```

Java parity (needs the exported files): `$env:SILERO_FRONTEND_DIR='<out>'; .\gradlew.bat :app:testDebugUnitTest --tests "*SileroFrontendTest"`.

## Results

- `verify_onnx.py`: ONNX vs scripted original max abs sample diff ≤ 2e-4; sherpa-onnx output equals onnxruntime.
- `host-validation.json` (11 sentences, all speakers/types): SNR 73–106 dB vs original, log-mel MAE ≤ 2.4e-5; faster-whisper small WER original 0.070 / ONNX 0.050 (difference is one ASR flip on near-identical audio); RTF on Ryzen 9 7945HX: onnxruntime 2 threads 0.048, sherpa-onnx 2 threads 0.067, original TorchScript 0.108.
- `frontend-parity.json`: Java `SileroStress` reproduces the package's homograph and accentor output on all 17 sentences.
- Android emulator (x86_64, API 36, sherpa-onnx 1.13.8 AAR, 2 threads): cold request-to-first-PCM 4.5 s (frontend load 1.4 s, ORT session 1.4 s), warm first PCM 0.09-0.37 s (a BERT homograph adds ~0.3 s), 9.1 s of audio in 1.25 s after load (RTF ~0.14 incl. stress); rate 2.0/0.5 gives 0.55x/1.88x duration (native durations, fixed final pause); Stop on first PCM leaves no late PCM; faster-whisper transcripts match the input text. Emulator numbers are not phone numbers.

## Not carried over

SSML, `**emphasis**`/focus, random voices, word timestamps. Upstream drops digits and Latin letters; the app spells integers out (nominative cardinals) and still drops Latin. Each sentence (≤ 200 chars) is synthesized separately, so the homograph solver sees only its own sentence.
