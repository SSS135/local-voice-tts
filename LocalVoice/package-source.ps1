param([string]$OutputPath = '', [switch]$ListOnly)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'SOURCE-PACKAGE.json') -Raw | ConvertFrom-Json
if ($manifest.schemaVersion -ne 1) { throw 'Unsupported source package schema' }
$members = [System.Collections.Generic.List[object]]::new()
function Add-MemberFile([string]$Source, [string]$Destination) {
    $sourceFile = Get-Item -LiteralPath $Source -ErrorAction Stop
    if ($sourceFile.PSIsContainer -or ($sourceFile.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Not a regular source file: $Destination" }
    $name = $Destination.Replace('\','/')
    if ($name -match '(^|/)(\.gradle|build|build-cache|signing|downloads|models|android-home|__pycache__)(/|$)' -or
        $name -match '(?i)(store-password\.txt|signing\.local\.ps1|local\.properties|private-vn-prefs-backup\.xml)$' -or
        $name -match '(?i)\.(keystore|jks|p12|pfx|pem|key|pkcs12|onnx|bin|safetensors|pt|pth|gguf|tflite|part|apk|aab|wav|mp3|avd|pyc|log)$' -or
        $name -match '(^|/)\.\.(/|$)') { throw "Excluded/private artifact encountered: $name" }
    if (!$sourceFile.FullName.StartsWith($workspace + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Source file escaped workspace' }
    $members.Add([pscustomobject]@{ Source=$sourceFile.FullName; Name=$name })
}
foreach ($relative in $manifest.projectFiles) { Add-MemberFile (Join-Path $PSScriptRoot $relative) ('LocalVoice/' + $relative) }
foreach ($relative in $manifest.optionalProjectFiles) {
    $optional = Join-Path $PSScriptRoot $relative
    if (Test-Path -LiteralPath $optional) { Add-MemberFile $optional ('LocalVoice/' + $relative) }
}
foreach ($tree in $manifest.projectTrees) {
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot $tree) -Recurse -File) {
        $relative = $file.FullName.Substring($PSScriptRoot.Length + 1)
        Add-MemberFile $file.FullName ('LocalVoice/' + $relative)
    }
}
foreach ($relative in $manifest.workspaceFiles) { Add-MemberFile (Join-Path $workspace $relative) $relative }
$ordered = @($members | Sort-Object Name)
if (@($ordered.Name | Select-Object -Unique).Count -ne $ordered.Count) { throw 'Duplicate source package member' }
foreach ($member in $ordered) { Write-Output $member.Name }
Write-Output 'SOURCE-FILES.sha256'
if ($ListOnly) { return }
if (!$OutputPath) { $OutputPath = Join-Path $workspace ('dist\LocalVoice-source-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.zip') }
$output = [IO.Path]::GetFullPath($OutputPath)
if (Test-Path -LiteralPath $output) { throw 'Output already exists; choose a new name so existing deliveries are preserved.' }
New-Item -ItemType Directory -Force -Path (Split-Path $output -Parent) | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open($output, [IO.Compression.ZipArchiveMode]::Create)
try {
    $hashLines = foreach ($member in $ordered) {
        $null = [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $member.Source, $member.Name, [IO.Compression.CompressionLevel]::Optimal)
        (Get-FileHash -LiteralPath $member.Source -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $member.Name
    }
    $entry = $zip.CreateEntry('SOURCE-FILES.sha256')
    $stream = $entry.Open()
    $writer = [IO.StreamWriter]::new($stream, [Text.UTF8Encoding]::new($false))
    try { $writer.WriteLine(($hashLines -join "`n")) } finally { $writer.Dispose() }
} finally { $zip.Dispose() }
$checkZip = [IO.Compression.ZipFile]::OpenRead($output)
try {
    foreach ($line in $hashLines) {
        $expected, $name = $line -split '  ', 2
        $stream = $checkZip.GetEntry($name).Open()
        try { $actual = (Get-FileHash -InputStream $stream -Algorithm SHA256).Hash.ToLowerInvariant() }
        finally { $stream.Dispose() }
        if ($actual -ne $expected) { throw "Source changed during packaging or ZIP checksum mismatch: $name" }
    }
} finally { $checkZip.Dispose() }
$digest = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
$digest + '  ' + [IO.Path]::GetFileName($output) | Set-Content -LiteralPath ($output + '.sha256') -Encoding ASCII
Write-Output "Archive: $output"
Write-Output "SHA-256: $digest"
