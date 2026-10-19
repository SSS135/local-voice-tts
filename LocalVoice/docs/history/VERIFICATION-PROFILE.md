# Model verification profile

## Current 1.1.1 patch scope

Version1.1.1/code3 uses one stored-byte SHA per downloaded payload and no duplicate streamed SHA. Fsync, pinned size/hash, strict path checks, final file barriers, receipt and atomic activation remain. Eligible fresh large files use bounded job-local held-descriptor/0400 proofs; weak generic cache records, old staging and imports keep conservative validation. Whole-job **Downloading and checking** progress is coalesced at200 ms; final/terminal phases remain immediate. The new host suites cover cancellation/publication, mode/identity faults, network-window path substitutions, old-stage retry and aggregate progress. Windows permission/link skips require separate real Android gates.

**Final execution:** the bounded 16-run comparison and exact-candidate focused checks completed. See [RELEASE-1.1.1.md](RELEASE-1.1.1.md) for results, limitations and final self-audit scope. Independent final binary/runtime review remained pending model capacity; this is stated explicitly rather than treated as an independent pass.

Final comparison is bounded to one ABBAABBA block per model,8 Kokoro plus8 Supertonic runs, using identical pinned warm local inputs into fresh shared UI/store roots with natural visible Installed draw. No further extension or v6 is planned. Earlier revisions remain in workspace `evidence/patch-1.1.1/REPORT-v2.md`, `REPORT-v3.md` and `REPORT-v4.md`: their verification tails improved but full Kokoro results were mixed. The final record is `REPORT-v5.md` with raw `v5/` observations. These are local-input emulator measurements, not Internet-transfer or physical-phone claims. Exact final-candidate runtime/timing results are recorded after the final bounded run.

The previously proposed32-run/5%/MAD policy was superseded before any final measurement. It remains history, not executed evidence. The following sections preserve historical1.0/1.1.0 measurements and must not be treated as the current1.1.1 implementation or relabelled as its final runtime proof.

These results combine actual Android storage profiles, host JVM components and deterministic host tests, measured on 18 October 2026. Android results use one API-36 x86_64 emulator with the same existing pinned model datasets. They are not phone benchmarks, speech-generation timings or end-to-end network download speedups.

## Android comparison

The counters-only 1.0 storage baseline and revised implementation ran the same `store-profile` instrumentation. Each model first undergoes a complete verification, then an immediate second verification, followed by 100 calls to `isInstalled()` on the main thread. **Every poll asserts that the model is installed.** The cached-retry test moves the installed directory to staging, removes its receipt, reinstalls from those existing files and asserts successful installation. Neither dataset is downloaded or decompressed during this workload.

| Workload | Kokoro before → revised | Supertonic before → revised |
| --- | ---: | ---: |
| 100 main-thread installed-state calls, wall | 11,149.22 → 19.18 ms | 2,637.66 → 19.87 ms |
| Cached retry, wall | 3,194.08 → 1,145.62 ms | 5,303.97 → 568.79 ms |
| Cached retry, bytes hashed from disk | 345,463,864 → 172,856,837 | 290,621,550 → 145,310,775 |
| Cached retry, full-file hash calls | 722 → 363 | 16 → 8 |
| First file verification, bytes hashed | 172,731,932 → 172,731,932 | 145,310,775 → 145,310,775 |
| Immediate second file verification, wall | 122.84 → 107.30 ms | 5.02 → 8.23 ms |

The revised cached retry removes 172,607,027 disk-hash bytes for Kokoro and 145,310,775 for Supertonic. Kokoro completes one full 361-file pass and conservatively rehashes two files totaling 124,905 bytes; the other 359 final checks are cache hits. Supertonic completes one eight-file pass, followed by eight cache hits. Both first verifications still hash the complete pinned model, so this is not a cold-validation skip. Kokoro's immediate second verification likewise rehashes those two files; Supertonic's performs metadata checks without a full hash. The stronger warm path is not uniformly faster: Supertonic's second check took approximately three milliseconds longer in this run.

Wall timings are single emulator observations. The baseline ran before the revised build, with different boot/JIT/filesystem-cache conditions. First-verification wall time fell from 7,186.88 to 649.44 ms for Kokoro and 2,746.43 to 527.71 ms for Supertonic **while reading the same model bytes**. Those large differences cannot be attributed to removing checksum passes. The reduced cached-retry hash bytes are stronger algorithmic evidence than a wall-time speedup ratio.

The 100-poll rows measure a ready installed-state snapshot, seeded by successful worker verification. They do not measure initial UI readiness after cold startup. Source review and the isolated production-Store host test establish that the local main-thread polling branch itself performs no model-file I/O. Android counters are process-wide: concurrent snapshot refresh contributed one 14-byte receipt read, 157 metadata calls and 310 canonicalizations to Kokoro's revised polling row. Supertonic's revised polling row recorded zero such aggregate work. Do not describe the entire Kokoro process row as zero I/O.

Baseline 100-poll aggregate work was 100 receipt reads, 72,500 metadata calls and 72,400 canonicalizations for Kokoro, versus 100 / 1,900 / 1,800 for Supertonic. Cached-retry metadata calls changed from 3,620 to 2,553 for Kokoro and 90 to 67 for Supertonic. Canonicalizations increased from 3,620 to 5,088 and 90 to 124 respectively: the final integrity barrier preserves path checks instead of using an unsafe path shortcut.

The revised sampled phase trace exposes `Checking files`, `Verifying` and `Finalizing`; short phases can fall between the probe's 20 ms polls. Kokoro's sampled checking/verification/finalization intervals were 839 / 61 / 113 ms. Supertonic's checking interval was 528 ms. Baseline cached retries appeared as `Queued` during their long checksum work. These are sampled phase observations, not precise component CPU timings. Posted change notifications increased from 3 to 11 for Kokoro and 3 to 9 for Supertonic because explicit phase boundaries are visible. No UI listeners were attached to this headless profile, so these are not measured screen repaint counts. Routine progress throttling is tested separately.

Raw workspace evidence: `evidence/verification-profile/android-baseline.json`, `android-optimized.json`, their `*-run.txt` instrumentation outputs, and derived `android-analysis.json`. The profile source is `app/src/androidTest/java/org/localvoice/tts/NativeBenchInstrumentation.java`; successful runs assert all 100 polls and final retry readiness. The preserved baseline app SHA-256 is `fa0fa8aa568cf2fb71a9300982d386e0fa6e4bb1a08378d6b081cd243e024351`; its instrumentation SHA-256 is `8aa90d01ad63e0264df5d3195e4c7b81e8a99b193eba841e5204d708c8e26c2a`. These instrumented artifacts are separate from the frozen signed 1.0 production APK.

The preserved revised profile app (`optimized-instrumented-app.apk`) has SHA-256 `971c81918b5505709018b718a55f5945ced3055ea4e3d7a91a2bbb948c3330b4`. Its `optimized-instrumentation.apk` has the same `8aa90d01…8c26c2a` SHA-256 as the baseline instrumentation above. All four artifacts reside under `evidence/verification-profile/`; their hashes were independently checked before later builds. They are profiling/debug artifacts, not a final signed 1.1 release.

## Baseline and matching datasets

The original 1.0.0 install algorithm checks every cached staging file twice. A fresh transfer writes and fsyncs a file, then reads it again for SHA-256. Opening the installed model can add another complete checksum pass. UI installation polling also reads the receipt and walks model files.

The counters-only baseline preserves this algorithm. Its sources are retained under workspace `evidence/verification-profile/baseline-source/`. The engine owner preserved baseline app/instrumentation APKs separately, so Android comparison can use the unchanged algorithm.

The host checksum profile uses the exact catalog-pinned files, sizes and SHA-256 values. It performs the two checksum passes from a fully cached retry, without copying model weights or accessing the network.

| Dataset | Pinned files | Dataset bytes | Baseline hash bytes / calls | Baseline wall time | Revised fallback hash bytes / calls | Revised fallback wall time |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Kokoro | 361 | 172,731,932 | 345,463,864 / 722 | 4.690 s | 345,463,864 / 722 | 4.098 s |
| Supertonic | 8 | 145,310,775 | 290,621,550 / 16 | 0.889 s | 290,621,550 / 16 | 0.568 s |

Windows lacks the Unix ctime identity used by the Android fast cache. The revised host profile therefore correctly falls back to two full checksum passes. **No reduction in read bytes occurred in this comparison.** Wall-time differences include filesystem-cache and JIT effects and are not evidence of an algorithmic speedup. Canonical path calls remain 1,444 / 32 respectively. Metadata calls increase from 1,444 to 2,888 for Kokoro and 32 to 64 for Supertonic because the portable fallback attempts Unix attributes, then reads basic attributes before and after hashing.

Raw JSONL and pinned TSV manifests: workspace `evidence/verification-profile/host-baseline/` and `host-cache-verified/`. `host-cache/` contains an unsuccessful argument-plumbing run and is not a measurement.

## Streaming checksum component

A separate matching workload reads each pinned source file locally, writes a temporary destination and fsyncs it. The baseline then reads the destination for SHA-256. The revised component hashes bytes during the write and returns a file-identity certificate captured at fsync completion. Both verify the exact pinned size and hash. One temporary file is reused; no additional installed model is retained.

| Dataset | Baseline wall / post-write hash bytes | Streaming wall / post-write hash bytes | Stream-hashed bytes | fsync count, both |
| --- | ---: | ---: | ---: | ---: |
| Kokoro | 6.666 s / 172,731,932 | 6.067 s / 0 | 172,731,932 | 361 |
| Supertonic | 2.727 s / 145,310,775 | 2.645 s / 0 | 145,310,775 | 8 |

This isolates transfer/checksum work. It excludes the final package-validation barrier, Android, HTTP and UI costs. The conservative final validation still reads fresh, coarse-identity or API-26 files. Do not interpret the removed component read as zero disk reads for a complete installation. Raw JSONL: workspace `evidence/verification-profile/host-transfer/`.

The baseline fsync component alone took 4.088 s for Kokoro and 2.254 s for Supertonic in this run. Fsync remains enabled; progress reports finalization rather than hiding this work. Direct-file Kokoro and Supertonic installation performs zero extraction/decompression. Model-pack import has separate extraction counters.

## Cache and publication boundaries

The cache is process-local and keyed by expected revision, size, SHA-256, device, inode, mtime and ctime. Android API 27+ supplies nanosecond fields. A fractional ctime must also be at least two seconds old before it can authorize a fast cache hit. Fresh, future, rounded/coarse or API-26 timestamps cause a complete checksum pass. A cold process always starts without verified-file records. Size plus mtime alone never authorizes a hit.

Streaming results carry their observed identity into the cache; a later unrelated stat cannot bless changed bytes. Files are written only inside the unadvertised staging directory. A partial file is checked in full and overwritten from byte zero on retry. Every file must pass pinned integrity checks, followed by a whole-model identity barrier immediately before receipt publication and atomic directory activation. Cancellation and publication share a lock. App-owned mutations are serialized; this does not claim protection against a privileged process racing arbitrary writes after the final check.

## UI and cancellation checks

`Progress` preserves `state/detail/bytes/total/running` and adds `phaseBytes/phaseTotal/filesDone/filesTotal`. File checking, download, verification and finalization are separate phases. Routine progress notifications are throttled to at most five per second. Explicit phase boundaries and terminal updates bypass the throttle, and pending main-thread notifications are coalesced.

Local-model `isInstalled()` calls on the main thread return worker-owned snapshots and schedule background refresh. The actual production `ModelStore` was compiled against small test-only Android/catalog boundaries, using real filesystem fixtures:

- 100 UI polling cycles, including 200 `isInstalled()` calls, performed zero file stat, canonicalization, receipt-read or checksum I/O on the calling thread.
- A fully cached retry activated the complete model atomically; the weak host identity intentionally retained two checksum passes.
- Same-size corruption with restored mtime was rejected before inference; the receipt and installed snapshot were invalidated.
- Queued cancellation and removal prevented stale publication.
- 5,000 progress writes produced one throttled notification and one immediate forced finalization notification in the recorded run.

The 23 pure integrity checks additionally exercise strong-cache reuse, cold/revision invalidation, same-tick timestamp collision, metadata changes during hashing, post-stream certificate mutation, the final all-file barrier, interrupted transfer/retry, cancellation and bounded sizes. Weak proofs cannot become trusted merely by aging, finishing a long hash, being remembered or being moved into the installed directory. Raw output: `build/verification/storage-integrity.txt`, `storage-store.txt`, and `storage-selfcheck.txt`.

The final host `ModelStore` harness also holds its shared executor busy while disabling Edge: consent and voice discovery change immediately. It checks successful model-pack atomic activation, queued cancellation before acquiring a grant, release of job-owned URI grants, preservation of preexisting grants, storage rejection before reading the selected file, bad archive rejection, and off-main startup reconciliation of stale app-owned grants. SharedPreferences boundaries are test shims; this is not an Android permission-lifecycle test.

Repair regressions run the production store with real files and a test-only HTTPS connection boundary, without opening sockets. A warmed verified model is damaged without changing size or mtime, rejected, and salvaged into staging. Only the bad file is fetched; every output pin and the final receipt are checked. Cancellation after the directory move leaves an unavailable but reusable staging cache, and explicit retry again fetches only the damaged file. A preexisting staging directory is preserved even when its good file conflicts with the damaged installed copy. A valid installed model is retained without moving or fetching. Raw evidence: workspace `evidence/model-repair-checks.txt` and the full `model-store-checks.txt`. This does not establish Android HTTP/provider behavior.

## Model-pack source validation

Twenty pure importer checks exercise missing/extra/duplicate/directory/path-escape entries, exact sizes, runtime/manifest/archive checksums, CRC, truncated payload/central-directory/EOCD, cancellation while reading or extracting, provider failure, bounded archive plans, explicit retry and truthful extraction counters. Malformed test fixtures recompute their outer archive pin so the tests exercise the inner ZIP defenses rather than stopping only at the archive checksum. The importer spools compressed bytes privately for complete ZIP validation and must remove that spool before returning success.

The actual existing Shtorm pack was separately imported on the host against the production catalog: archive 440,049,147 bytes, fourteen runtime files totaling 440,043,847 bytes, and the exact embedded 3,610-byte manifest. All archive/manifest/runtime hashes and ZIP/CRC checks passed. The manifest is checked without extraction, so counters report 440,047,457 decompressed bytes and fourteen extracted runtime files. A further independent checksum pass verified the extracted outputs; no receipt was published and the entire temporary tree was deleted. The 17.217-second host functional run includes fsync and does not predict Android install or native speech speed.

Workspace evidence: `evidence/model-pack-import-checks.txt`, `model-store-checks.txt`, `actual-shtorm-pack-import-check.txt`, and `shtorm-text-checks.txt`. The last records 81 expected Russian-cardinal/prompt assertions, including numeric format limits and bounded chunks after expansion. Byte counters observe the explicitly instrumented managed copy/hash paths; internal ZIP directory/decompression reads are not a complete OS I/O trace.

## Reproduction

Run with an existing JDK; these commands do not build an APK or launch an emulator:

```powershell
.\tests\run-storage-integrity.ps1
.\tests\run-storage-store.ps1
.\tests\run-storage-selftest.ps1 -JsonJar <Android-compatible-org.json-test-jar>
.\tests\run-model-pack-import.ps1 -JsonJar <Android-compatible-org.json-test-jar>
.\tests\run-shtorm-text.ps1
.\tests\profile-storage-hashes.ps1 -Mode baseline -OutputDirectory <new-output-directory>
.\tests\profile-storage-hashes.ps1 -Mode cache -OutputDirectory <another-new-output-directory>
.\tests\profile-storage-transfer.ps1 -ManifestDirectory <baseline-output-directory> -OutputDirectory <new-output-directory>
```

Host model profiles require the development workspace's exact pinned research datasets; model weights are intentionally excluded from the source distribution. Scripts refuse to overwrite existing profile evidence. A pure strong-identity test uses an injected identity reader to prove the cache rule; it is not presented as an Android measurement.

To repeat the optional actual-pack functional check, pass `-ArchivePath <existing-pinned-Shtorm-ZIP>` to `run-model-pack-import.ps1`. This uses bounded temporary disk space, verifies the existing package without downloading/converting it, and deletes its temporary extraction.

## Later Android install and speech acceptance

Separate real Android workflows now cover the 126,176,567-byte English Pocket model download, cancellation with completed-file cache reuse on retry, and the 440,049,147-byte Shtorm SAF import. Shtorm was cancelled near 32% while reading the package; after explicit file re-selection, extraction and installation completed. These are functional workflow checks rather than matched transfer-speed benchmarks or measured UI repaint totals. Evidence includes the `1.1-pocket-en-*-download.log` history, `1.1-shtorm-reading-before-cancel.png`, `1.1-shtorm-real-cancelled.png` and `1.1-shtorm-retry-*` screenshots/XML.

The actual Pocket English `README.md` was changed at the same size with its original mtime restored. Verification rejected it, invalidated its receipt/voice and made no automatic download. Explicit UI repair transferred only that pinned 5,251-byte README and restored the installation. Workspace evidence: `1.1-restored-mtime-corrupt.txt`, `1.1-corrupt-no-automatic-download.log`, `1.1-corrupt-voice-check-events.json`, `1.1-tiny-repair-download.log` and `1.1-pocket-en-tiny-repair.*`. This complements the fake-transport repair regressions; it does not measure an adversarial privileged writer racing publication.

The D129 signed candidate (`d1291ab6f566b6fda4f1b76f50ddccc905f823e7c2cae7c27813b3df6cdbb591`, 69,584,186 bytes) passed the offline public-client suite with settled active network `None`: Kokoro/Pocket English/Shtorm Russian produced nonempty 24 kHz PCM, and Supertonic English/Russian produced 44.1 kHz PCM. Both Pocket adapters stopped after 36,864 PCM bytes without later PCM or successful done; replacement, process restart and unsupported Shtorm text error also passed. Pocket rate 0.75 / 1.25 produced 3.200 / 1.728 seconds. Stochastic native generation means this is a duration-direction check, not an exact inverse-ratio proof. Host Sonic signal tests support pitch preservation; these Android tests do not establish subjective voice quality. Raw evidence: `1.1-offline-suite-summary.json`, `1.1-offline-suite-run.txt` and matching WAV/event files.

Edge cold headless discovery exposed 372 voices including 322 network voices; disabling it exposed 50 local/zero network voices. An offline request remained pending until explicit Disable, errored without PCM/done, and did not resume when Wi-Fi returned. The callback's 236,398 ms timestamp includes a frozen client and is not engine latency; the live observed wait before revocation was about 160 seconds. French `fr-FR-DeniseNeural` produced real 24 kHz PCM. Evidence: `1.1-edge-cold-pending-events.json`, `1.1-edge-revoked-events.json`, `1.1-edge-revoked-after-network-events.json`, `1.1-edge-disabled-voice-check-events.json` and `1.1-final-edge-fr-*`.

The subsequent signed `8ebd07c0f4da705d00745bc1e7410b6ad6233ee9e0ec8e55f59fe32474fc170a` candidate changes only `CheckVoiceDataActivity` to inspect local resources on a worker. Its cold public check-data result passed at the probe's 527 ms event timestamp (`eng-USA`, `eng-GBR`, `rus-RUS`, missing `[]`); this is the probe timeline, not an inference latency. The check deliberately covers local data, while modern discovery includes enabled network voices. Evidence: `1.1-cold-check-data-fixed-run.txt` / `1.1-cold-check-data-fixed-events.json`.

The exact 8eb candidate subsequently passed offline Pocket English and Shtorm Russian smoke with successful done/no error: 55,680 / 69,120 frames, respectively, both at 24 kHz (111,360 / 138,240 PCM bytes). Active network was `None`. Raw evidence: `1.1-final8-smoke-summary.json`, `1.1-final8-smoke-run.txt` and matching WAV/events. Production Edge English/Russian on those bytes also completed with 86,976 / 126,144 frames at 24 kHz (`1.1-final8-edge-*`). These smoke checks complement rather than replace the broader D129 suite.

Earlier portrait UI discovery/filter checks passed with 372 voices, including the single Shtorm selection. Measured touch targets were 48 dp for the slider/Listen/Cancel and 56 dp for bottom navigation. These initial checks were extended by the final matrix described below.

## Final artifact and closeout

Final LocalVoice 1.1.0/code 2 is `1da7f5dbc38c95b0cc570275572570c26c8e305c7f3f3f7cc5907ffc89ef700c`, 69,584,186 bytes, with the original certificate SHA-256 `d7e7cbdd26574111fbcfb333b3c4bcb2c75e000df4551fab3fe5022282183ba8`. The only change after 8eb was bottom-navigation text size 14→12 sp. Final exact-artifact Pocket English/Russian offline smoke passed at 57,600 / 65,280 frames, respectively, both 24 kHz with done/no error; its cold local data-check event at 513 ms passed for English US/UK and Russian, missing `[]`. Raw evidence: `1.1-final1da7-smoke-summary.json`, matching WAV/events and `1.1-final1da7-cold-data-events.json`. The full D129 suite remains lineage evidence and is not relabeled as an exhaustive suite on the final bytes.

The completed UI matrix covered one-line 56 dp navigation at 360 dp width/font scale 1.5, landscape Listen/IME, dark theme, About/full-license scrolling, and three stacked 48 dp large-font Details actions. The slider's measured 126 px at density 2.625 equals 48 dp. Exact Alba MacKenna preview isolation was verified: a public uncountried English default remained Kokoro after preview, while deliberate voice-row selection persisted. The earlier broad `alba` search trial selected an Edge voice and is excluded from Pocket preview evidence.

The UI 1.5× multiplier and caller 1.25× rate produced the expected 1.875× composition once. Supertonic's output was 1.490816 s against the fixed-phrase 1× baseline 2.795283 s, ratio approximately 1.875002; the saved multiplier was restored to 1.0. Evidence: `1.1-global-caller-composition.json` and matching public-client events/WAV/UI capture.

Existing D129 one-request observations are summarized in `1.1-performance-summary.json`: public request→first PCM was Kokoro 8,065 ms, Supertonic English/Russian 3,272 / 2,536 ms, Pocket English 2,928 ms and Shtorm 7,168 ms. Corresponding process PSS snapshots were 323,277; 273,164 / 269,660; 492,101; and 1,181,275 KiB. These are separate invocations, not controlled cold/warm pairs; PSS includes the whole process/runtime/allocator state after switching and is not isolated model memory or an allocation peak. The historical 1.0 same-binding cold/warm pairs remain separate; no formal new Pocket warm pair was measured.

Unchanged VN Reader 3.4.4 used its System TTS provider with saved `pocket-en-alba:alba` and `org.localvoice.tts` selected during the preview. Service logs record ready, first PCM and done; no separate VN-specific WAV or subjective listening evaluation was made. Its installed APK remained unchanged and preferences were restored byte-for-byte. The original Android TTS default, font/rotation/display/night/network settings and network speed were restored. The engine owner stopped the sole owned test AVD after verification. Evidence: `1.1-vn-system-preview.log`, provider/preview screenshots/XML and `1.1-vn-integration-restoration.json`.

Runtime and document acceptance is complete. Source/evidence packaging and external delivery JSON / `SHA256SUMS` are separate operations; see [SOURCE-PACKAGING.md](../SOURCE-PACKAGING.md). No physical-phone, battery, thermal or subjective-quality benchmark has been made.
