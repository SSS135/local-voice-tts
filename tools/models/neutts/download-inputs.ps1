# Fetches and verifies the pinned NeuTTS inputs into tools/models/neutts/build/ (ignored by git).
# Gated Hugging Face files need a token with accepted Neuphonic terms: $env:HF_TOKEN or ~/.cache/huggingface/token.
$ErrorActionPreference = 'Stop'
$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'source-input-manifest.json') -Raw | ConvertFrom-Json
$dir = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$token = $env:HF_TOKEN
$tokenFile = Join-Path $HOME '.cache/huggingface/token'
if (!$token -and (Test-Path -LiteralPath $tokenFile)) { $token = (Get-Content -LiteralPath $tokenFile -Raw).Trim() }
foreach ($item in $manifest) {
    if ([IO.Path]::GetFileName($item.file) -ne $item.file) { throw 'Unsafe input path' }
    $target = Join-Path $dir $item.file
    if (!(Test-Path -LiteralPath $target)) {
        $auth = @()
        if ($item.gated) {
            if (!$token) { throw ('Hugging Face token required for ' + $item.file) }
            $auth = @('-H', ('Authorization: Bearer ' + $token))
        }
        & curl.exe -L --fail --silent --show-error @auth $item.url -o $target
        if ($LASTEXITCODE -ne 0) { throw ('Download failed: ' + $item.file) }
    }
    if ((Get-Item -LiteralPath $target).Length -ne $item.size -or (Get-FileHash -LiteralPath $target).Hash.ToLowerInvariant() -ne $item.sha256) { throw ('Input checksum failed: ' + $item.file) }
    Write-Output ('Verified ' + $item.file)
}
