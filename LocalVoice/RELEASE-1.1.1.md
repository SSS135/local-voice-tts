# LocalVoice 1.1.1 verification

Version **1.1.1/code 3**, package `org.localvoice.tts`. Signed universal APK: **69,584,186 bytes**; SHA-256 `4bf81fde2449a19775bdca5ea393eb2f55faf25b25486c0c73810c5a794582f0`. Same certificate SHA-256 `d7e7cbdd26574111fbcfb333b3c4bcb2c75e000df4551fab3fe5022282183ba8`.

The patch removes the duplicate streamed digest while retaining one stored-byte SHA per fresh downloaded file, strict path checks, bounded job-local descriptor proofs, conservative fallbacks, cancellation and atomic publication. Whole-job progress is coalesced; unchanged UI text is not rewritten. Imports and generic weak-cache trust are not relaxed.

## Final bounded comparison

One ABBAABBA block per model: four baseline/patch pairs each, **16 total runs**, all retained. Baseline is frozen 1.1.0; observer copies use identical pinned warm local inputs, unique empty shared UI/store roots and natural fully visible Installed draws. This measures local-input installation on one x86_64 Android emulator, not Internet transfer or physical-phone performance.

| Dataset | Start → atomic, median ms | Final input byte → atomic, median ms | Final input byte → visible Installed, median ms |
| --- | ---: | ---: | ---: |
| Kokoro (361 files; 172,731,932 bytes) | 4693.52 → 3659.71 | 612.19 → 270.78 | 620.01 → 283.18 |
| Supertonic (8 files; 145,310,775 bytes) | 1197.52 → 1050.50 | 323.36 → 16.39 | 345.54 → 40.83 |

All four paired measurements improved for each metric/model. Kokoro ranges were separated; Supertonic full-install ranges overlapped. These are descriptive observations, without a population/statistical-confidence claim. No additional runs or optimization revision followed. Earlier v2–v4 negative/mixed results remain in the evidence history; they are not pooled with this final set. The unused 32-run/MAD proposal was superseded before final data collection.

Disk SHA bytes remained exactly the pinned dataset size for both variants; streamed SHA bytes changed from that same size to zero. Stored-byte verification remains. Progress publications, notification/listener calls, actual UI setter calls and natural draw callbacks are retained per run; they are distinct counters, not interchangeable repaint measures.

## Exact-candidate checks

- Android path checks: clean nested install plus 12 before/after-response path-fault trials rejected root/staging/ancestor/leaf links, nondirectory parents and unexpected fresh files without outside writes or activation. Real Android symlink/no-follow checks passed.
- Actual native descriptors: 16 retained proofs; a 17th read descriptor was the sequential fallback checksum reader. Every tested success/cancel/input/mode/path terminal returned owned descriptors to zero. App-UID ordinary writes to sealed 0400 files failed with EACCES and unchanged bytes. Corrupt staged data was rejected and retry fetched only the bad file.
- Main-thread download cancellation returned in 0.5300 ms in the controlled job test. A later body stall after earlier files were sealed retained the global “Downloading and checking” state. The UI fixture observed zero text callbacks for 100 unchanged refreshes, and real progress/retry/availability/selection changes still rendered.
- Saved Alba and 1.3× remained across the final candidate update/new process, visible check/slider and public English-default checks. The original 1.1.0→initial-patch upgrade proof remains separately identified; it is not relabelled as a direct 1.1.0→final-APK trial.
- Offline public System TTS produced nonzero 24 kHz PCM/done for Pocket English and Shtorm Russian. Stop after first PCM returned in 21 ms, without late PCM or successful done during the subsequent observation; a fresh request completed afterward. Native work can outlive Stop, and already-buffered playback is not claimed to stop instantaneously.
- With local data reversibly hidden and online capability disabled, public discovery returned zero voices, English/Russian missing-data and CHECK failure. Restoring data returned 372 voices and CHECK pass. A fresh Shtorm SAF file-picker install from the existing pinned package passed verification and subsequent Russian synthesis. It completed too quickly for a new mid-import cancellation trial; no such claim is made.

Official apksigner/aapt and ZIP/DEX checks passed: same v2 signature, min SDK 26/target 36, no debug/test/observer components, no cleartext/backup, and no bundled weights/keys. Independent source/observer/earlier candidate reviews passed. **Independent review of this final binary/runtime archive remained pending model capacity; the execution owner performed the final static and archive self-audits.**

## Scope and closeout

Model quality, voices/catalog, JNI synthesis and Edge behavior were not redesigned. Previous 1.1.0 and acceptance-supplement evidence retains its original APK lineage, including VN Reader 3.4.5 System TTS integration; VN was not changed or retested in this patch cycle. No phone, quality, battery or thermal claim is made. Shtorm remains Install file: no hosted one-button package endpoint was added, no weights were repackaged/redistributed, and the existing hosting brief remains applicable. Online Edge is explicit opt-in; local failure does not silently send text online.

Original preferences, TTS default, display/night/rotation and guest network settings were restored. Owned emulator-5582 and launcher 63672 were stopped and verified absent; no shared ADB restart or original AVD modification occurred. All prior 17 public artifacts are preserved. The new APK/source/evidence/setup/report identities are recorded in the separate delivery manifest and SHA256SUMS.
