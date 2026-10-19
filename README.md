# Local Voice 1.1.1

Android system text-to-speech engine (`org.localvoice.tts`) with downloadable
Kokoro English, Supertonic English/Russian, Pocket English and Shtorm Russian
models, plus explicitly enabled online Microsoft Edge voices. Application source is GPL-3.0; native libraries
and models retain their own licenses. No model weights are committed here.

- App, build, tests and licensing: [LocalVoice/README.md](LocalVoice/README.md)
- Layout, commands and contributor rules: [AGENTS.md](AGENTS.md)
- Model/native provenance: [research/README.md](research/README.md)

## Build

Install JDK 17 or newer and Android SDK Platform 36, then in PowerShell:

```powershell
cd LocalVoice
.\fetch-runtime.ps1
.\build.ps1 -JavaHome 'C:\path\to\jdk' -AndroidSdk 'C:\path\to\Android\Sdk'
```

`fetch-runtime.ps1` fetches the sherpa-onnx 1.13.8 AAR and verifies its size and
SHA-256. Source deliveries additionally need `.\research\native-source\fetch-sources.ps1`
before `LocalVoice\package-source.ps1`.

## Provenance

Imported from `LocalVoice-source-1.1.1.zip`, SHA-256
`b21d43e8ab334b1ed3ea4e51552afc99d6903eec493c8020b7ead575916be628`
(1.0.0: `f50b518a24fd0390b2e1c1474094fd6a989938ef695f07730fb3d2c740e85e73`).
Release verification: [LocalVoice/docs/history](LocalVoice/docs/history/).
