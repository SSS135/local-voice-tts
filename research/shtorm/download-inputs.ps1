$ErrorActionPreference = 'Stop'
$manifest=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'source-input-manifest.json') -Raw | ConvertFrom-Json
foreach($item in $manifest) {
    if([IO.Path]::GetFileName($item.file) -ne $item.file) { throw 'Unsafe input path' }
    $target=Join-Path $PSScriptRoot $item.file
    if(!(Test-Path -LiteralPath $target)) {
        & curl.exe --noproxy '*' -L --fail --silent --show-error $item.url -o $target
        if($LASTEXITCODE -ne 0) { throw ('Download failed: '+$item.file) }
    }
    if((Get-Item -LiteralPath $target).Length -ne $item.size -or (Get-FileHash -LiteralPath $target).Hash.ToLowerInvariant() -ne $item.sha256) { throw ('Input checksum failed: '+$item.file) }
    Write-Output ('Verified '+$item.file)
}
Expand-Archive -LiteralPath (Join-Path $PSScriptRoot 'lookbe-source.zip') -DestinationPath (Join-Path $PSScriptRoot 'lookbe-src') -Force
Expand-Archive -LiteralPath (Join-Path $PSScriptRoot 'pocket-export-base.zip') -DestinationPath (Join-Path $PSScriptRoot 'pocket-export-base') -Force
Expand-Archive -LiteralPath (Join-Path $PSScriptRoot 'pocket-tts-source.zip') -DestinationPath (Join-Path $PSScriptRoot 'pocket-src') -Force
