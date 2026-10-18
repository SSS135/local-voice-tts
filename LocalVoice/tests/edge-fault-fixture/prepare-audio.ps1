param(
    [string]$InputWav,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../../build/edge-fixture'),
    [string]$Ffmpeg = 'ffmpeg.exe'
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$outputMp3 = Join-Path $OutputDirectory 'valid.mp3'
$outputPcm = Join-Path $OutputDirectory 'reference.pcm'
if ($InputWav) {
    & $Ffmpeg -hide_banner -loglevel error -y -i $InputWav -map_metadata -1 -ac 1 -ar 24000 -c:a libmp3lame -b:a 48k -write_xing 0 -id3v2_version 0 -write_id3v1 0 $outputMp3
} else {
    & $Ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'sine=frequency=440:sample_rate=24000:duration=1' -map_metadata -1 -ac 1 -ar 24000 -c:a libmp3lame -b:a 48k -write_xing 0 -id3v2_version 0 -write_id3v1 0 $outputMp3
}
if ($LASTEXITCODE -ne 0) { throw 'Fixture MP3 encoding failed' }
& $Ffmpeg -hide_banner -loglevel error -y -i $outputMp3 -ac 1 -ar 24000 -f s16le $outputPcm
if ($LASTEXITCODE -ne 0) { throw 'Fixture MP3 decoding failed' }
$report = [ordered]@{ source= $(if ($InputWav) {'local-wav'} else {'generated-440Hz-tone-1second'}); sampleRate=24000; channels=1; bitrate=48000; mp3Bytes=(Get-Item $outputMp3).Length; mp3Sha256=(Get-FileHash $outputMp3).Hash.ToLowerInvariant(); referencePcmBytes=(Get-Item $outputPcm).Length; referencePcmSha256=(Get-FileHash $outputPcm).Hash.ToLowerInvariant() }
if ($InputWav) { $report.sourceWavSha256=(Get-FileHash $InputWav).Hash.ToLowerInvariant() }
$report | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $OutputDirectory 'audio-provenance.json')
$report | ConvertTo-Json
