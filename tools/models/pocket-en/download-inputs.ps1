$ErrorActionPreference = 'Stop'
$manifest=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'runtime-manifest.json') -Raw | ConvertFrom-Json
foreach($item in $manifest.files) {
    if([IO.Path]::GetFileName($item.path) -ne $item.path) { throw 'Unsafe model path' }
    $target=Join-Path $PSScriptRoot $item.path
    if(!(Test-Path -LiteralPath $target)) {
        & curl.exe --noproxy '*' -L --fail --silent --show-error $item.url -o $target
        if($LASTEXITCODE -ne 0) { throw ('Download failed: '+$item.path) }
    }
    if((Get-Item -LiteralPath $target).Length -ne $item.size -or (Get-FileHash -LiteralPath $target).Hash.ToLowerInvariant() -ne $item.sha256) { throw ('Model checksum failed: '+$item.path) }
    Write-Output ('Verified '+$item.path)
}
