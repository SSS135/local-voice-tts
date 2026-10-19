$ErrorActionPreference = 'Stop'
$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'source-manifest.json') -Raw | ConvertFrom-Json
foreach ($item in $manifest) {
    $destination = Join-Path $PSScriptRoot $item.file
    if (!(Test-Path -LiteralPath $destination)) {
        & curl.exe --noproxy '*' -L --fail --silent --show-error $item.url -o $destination
        if ($LASTEXITCODE -ne 0) { throw ('Download failed: ' + $item.component) }
    }
    $actual = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $item.sha256 -or (Get-Item -LiteralPath $destination).Length -ne $item.size) {
        throw ('Source verification failed: ' + $item.component)
    }
    Write-Output ('Verified ' + $item.component + ' at ' + $item.commit)
}
