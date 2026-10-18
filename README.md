# Local Voice 1.0.0

Android system text-to-speech engine (`org.localvoice.tts`) with downloadable
Kokoro English and Supertonic English/Russian models, plus explicitly enabled
online Microsoft Edge voices. Application source is GPL-3.0; native libraries
and models retain their own licenses. No model weights are committed here.

The frozen application, probe, tests and architecture live in [LocalVoice](LocalVoice/).
Start with its [README](LocalVoice/README.md) and [architecture](LocalVoice/ARCHITECTURE.md).

## Build

Install JDK 17 or newer and Android SDK Platform 36, then run in PowerShell:

```powershell
cd LocalVoice
.\fetch-runtime.ps1
.\build.ps1 -JavaHome 'C:\path\to\jdk' -AndroidSdk 'C:\path\to\Android\Sdk'
```

`fetch-runtime.ps1` retrieves the official sherpa-onnx 1.13.8 AAR and verifies
its exact size and SHA-256 before installation. The runtime is ignored by Git.
The original app build configuration and model/checksum catalog are unchanged.
Builds use your own debug key or an explicitly configured private signing key.
No original signing material is stored in this repository.

## Native source and source packages

Pinned native source URLs, revisions, checksums, build files and licenses live
under [research/native-source](research/native-source/). Download and verify the
complete sherpa-onnx, piper-phonemize and eSpeak NG source snapshots with:

```powershell
.\research\native-source\fetch-sources.ps1
```

The large unmodified source archives are ignored by Git. Fetch them and the
runtime before running `LocalVoice\package-source.ps1` to produce a complete
source delivery, including required native source and license material. Read
the [native rebuild limitations](research/native-source/BUILD-AND-SOURCE-NOTES.txt).
The native runtime was not independently rebuilt or proved byte reproducible.

## Validation and provenance

The implementer's frozen release passed 14 backend contract checks, 26 retry
and audio fault checks, and documented emulator audio/recovery checks. Those
results and their limitations are recorded in the app README. Physical-phone
performance, Kokoro Russian, and Shtorm remain unverified or deferred.

The import comes from `LocalVoice-source-1.0.0.zip`, SHA-256
`f50b518a24fd0390b2e1c1474094fd6a989938ef695f07730fb3d2c740e85e73`.
Source member checksums were verified before import. Repository preparation
added this entry point and the runtime fetch helper, updated packaging/docs,
and normalized text line endings. It did not change application behavior.
Private review reports, keys, credentials, recordings, model downloads, caches,
APKs and generated build products are excluded.
