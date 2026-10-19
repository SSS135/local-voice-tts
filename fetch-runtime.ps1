$ErrorActionPreference = 'Stop'
$destination = Join-Path $PSScriptRoot 'app/libs/sherpa-onnx-1.13.8.aar'
$expectedSha256 = '633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96'
$expectedSize = 50129134
$url = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar'
function Assert-Runtime([string]$Path) {
    if ((Get-Item -LiteralPath $Path).Length -ne $expectedSize -or
        (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedSha256) {
        throw 'Runtime verification failed. Remove the invalid AAR and run this script again.'
    }
}
if (Test-Path -LiteralPath $destination) {
    Assert-Runtime $destination
    Write-Output 'Verified existing sherpa-onnx 1.13.8 runtime.'
    return
}
$temporary = $destination + '.part'
if (Test-Path -LiteralPath $temporary) { throw 'A partial runtime file already exists. Remove it before retrying.' }
New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent) | Out-Null
try {
    & curl.exe -L --fail --silent --show-error $url -o $temporary
    if ($LASTEXITCODE -ne 0) { throw 'Runtime download failed.' }
    Assert-Runtime $temporary
    Move-Item -LiteralPath $temporary -Destination $destination
    Write-Output 'Downloaded and verified sherpa-onnx 1.13.8 runtime.'
} finally {
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
}
