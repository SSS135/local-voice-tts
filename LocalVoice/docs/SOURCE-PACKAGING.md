# Source delivery boundary

`SOURCE-PACKAGE.json` is the explicit packaging allowlist. `.gitignore` is not a
delivery boundary. Run these host-only checks from the LocalVoice directory:

```powershell
.\tests\run-source-package-check.ps1
.\package-source.ps1 -ListOnly
.\package-source.ps1 -Audit
```

`-ListOnly` prints prospective ZIP members. `-Audit` prints their byte lengths and
SHA-256 values as JSON and reports `archiveCreated: false`; neither creates a ZIP.
Rerun the audit after the final source change because it describes the current
working files, not a frozen release. The rejection tests use a public README as
their input and unsafe proposed archive names, plus a temporary junction to a
harmless public fixture; they never open private files. The packager rejects
reparse-point files and ancestors before reading or hashing their content.

After source/version review and freeze, an explicit delivery command is:

```powershell
.\package-source.ps1 -OutputPath '..\dist\LocalVoice-source.zip'
```

The command refuses an existing output, writes `SOURCE-FILES.sha256` inside the
ZIP, reopens every source entry to verify its hash, and writes the whole ZIP's
SHA-256 beside it. Review the emitted member list before distribution. This
command packages source only; it does not build, sign, start an emulator, install
software, or publish anything. A separately assembled evidence archive needs its
own explicit reviewed members and per-entry hash manifest.

## Included corresponding source

The project files include app/probe production and instrumentation sources,
resources, full bundled Edge metadata, legal assets, host tests and their small
fixtures, design/catalog/frontend/verification documentation, Gradle wrapper and
the existing sherpa-onnx AAR. The minimal Sonic source is accompanied by its
original pinned AndroidX source, full Apache 2.0 license, reproduction/check
script and modification notice. SentencePiece and Darts notices remain in the
app's license assets beside the frontend's source and test-fixture provenance.

Six immutable upstream source ZIPs have exact lengths and SHA-256 pins in the
allowlist: the original three sherpa-onnx, piper-phonemize and eSpeak NG snapshots,
plus Shtorm's fork, exporter base and Pocket-TTS source snapshots. An archive
extension is accepted only at one of those six exact paths and only if its
length and digest match. The native build notes describe the remaining limits
on reproducing the published native libraries; packaging source does not prove
a byte-identical native rebuild.

Only individually named research files are included. The Shtorm kit contains
its portable environment/configuration/download/export/tokenizer/voice/native
proof/packaging recipes, source manifests, licenses and small parity results.
The English Pocket kit contains its download/manifest/export/host-eval/frontend
recipes, licenses and small proof results. See `research/shtorm/REPRODUCE.md` and
`research/pocket-en/REPRODUCE.md` in the delivery. Their explicit reproduction
commands can download upstream model inputs; those inputs are not bundled in
the source delivery. Exact model sizes/versions/voice claims belong to their
pinned model manifests and model documentation.

## Excluded artifacts

There is no recursive research-directory inclusion. Model weights, tokenizer
model binaries, generated ONNX graphs, reference/output audio, runtime model ZIP
packs, virtual environments, Python/native dependencies, caches, SDKs/AVDs,
build output, APKs and raw logs remain outside the source package. The pinned
upstream source archives are the only archive-file exception. The exploratory
English `model-api.json` is deliberately not part of the reproducible kit.

Signing keys, certificate/private-key containers, passwords, local signing/SDK
configuration, environment files, private preference backups, private review
notes and private workspace/tool directories are rejected by the packager even
if accidentally added to an allowlisted tree. Existing top-level Android test
harnesses with host-specific paths and obsolete release assumptions are not
promised as portable 1.1 tests; current project tests are under `LocalVoice/tests`
and Android instrumentation source. Evidence and public source must be reviewed
separately from private diagnostics.
