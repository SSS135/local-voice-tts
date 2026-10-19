# Fetches and verifies the pinned upstream inputs into tools/models/silero-ru/build/ (ignored by git).
$ErrorActionPreference = 'Stop'
$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'source-input-manifest.json') -Raw | ConvertFrom-Json
$dir = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
foreach ($item in $manifest) {
    if ([IO.Path]::GetFileName($item.file) -ne $item.file) { throw 'Unsafe input path' }
    $target = Join-Path $dir $item.file
    if (!(Test-Path -LiteralPath $target)) {
        & curl.exe -L --fail --silent --show-error $item.url -o $target
        if ($LASTEXITCODE -ne 0) { throw ('Download failed: ' + $item.file) }
    }
    if ((Get-Item -LiteralPath $target).Length -ne $item.size -or (Get-FileHash -LiteralPath $target).Hash.ToLowerInvariant() -ne $item.sha256) { throw ('Input checksum failed: ' + $item.file) }
    Write-Output ('Verified ' + $item.file)
}
