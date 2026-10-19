param([string]$OutputPath = '', [switch]$ListOnly, [switch]$Audit)
$ErrorActionPreference = 'Stop'
# The repository root is the workspace; every member is stored as 'LocalVoice/' + repo-relative path,
# matching the 1.0.0/1.1.x delivery layout (docs/SOURCE-PACKAGING.md).
$workspace = $PSScriptRoot
$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'SOURCE-PACKAGE.json') -Raw | ConvertFrom-Json
if ($manifest.schemaVersion -ne 1) { throw 'Unsupported source package schema' }
$members = [System.Collections.Generic.List[object]]::new()
function Add-MemberFile([string]$Source, [string]$Destination) {
    $sourceFile = Get-Item -LiteralPath $Source -ErrorAction Stop
    if ($sourceFile.PSIsContainer -or ($sourceFile.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Not a regular source file: $Destination" }
    $name = $Destination.Replace([char]92,[char]47)
    # tools/models/ holds model recipes; the models/ segment rule targets weight directories elsewhere.
    $checked = $name -replace '^LocalVoice/tools/models/', 'LocalVoice/tools/'
    if ($checked -match '(?i)(^|/)(\.git|\.codex|\.agents|\.aws|\.gradle|build|build-cache|signing|downloads|models|deps|native-deps|venv|\.venv|site-packages|android-home|\.android|__pycache__)(/|$)' -or
        $checked -match '(?i)(^|/)(store-password\.txt|signing\.local\.ps1|local\.properties|private-vn-prefs-backup\.xml|keystore\.properties|signing\.properties|secrets\.properties|\.env(?:\..*)?|REVIEW(?:-1\.1)?\.md)$' -or
        $checked -match '(?i)(\.local\.ps1|\.(keystore|jks|p12|pfx|pem|key|pkcs12|onnx|bin|model|safetensors|pt|pth|ckpt|pkl|pickle|npy|npz|gguf|tflite|part|apk|aab|wav|mp3|avd|qcow2|img|pyc|log))$' -or
        $checked -match '(^|/)\.\.?(/|$)' -or $name.StartsWith('/') -or $name.Contains(':') -or $name.Contains('//')) { throw "Excluded/private artifact encountered: $name" }
    if (!$sourceFile.FullName.StartsWith($workspace + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Source file escaped workspace' }
    # A regular leaf below a junction can still refer outside the workspace.
    # Inspect ancestor metadata before opening or hashing any file content.
    $sourceDirectory = $sourceFile.Directory
    while ($sourceDirectory -and !$sourceDirectory.FullName.Equals($workspace, [StringComparison]::OrdinalIgnoreCase)) {
        if ($sourceDirectory.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Reparse-point source ancestor: $name" }
        $sourceDirectory = $sourceDirectory.Parent
    }
    if ($name -match '(?i)\.(zip|tar(?:\.gz|\.bz2|\.xz|\.zst)?|gz|bz2|xz|zst|tgz|tbz2|7z|rar)$') {
        $allowed = @($manifest.sourceArchives | Where-Object { 'LocalVoice/' + $_.path -eq $name })
        if ($allowed.Count -ne 1 -or $allowed[0].sha256 -notmatch '^[a-f0-9]{64}$' -or
            $sourceFile.Length -ne $allowed[0].bytes -or
            (Get-FileHash -LiteralPath $sourceFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant() -ne $allowed[0].sha256) {
            throw "Unlisted or changed source archive (model packs are excluded): $name"
        }
    }
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
foreach ($relative in $manifest.workspaceFiles) { Add-MemberFile (Join-Path $workspace $relative) ('LocalVoice/' + $relative) }
$ordered = @($members | Sort-Object Name)
if (@($ordered.Name | Select-Object -Unique).Count -ne $ordered.Count) { throw 'Duplicate source package member' }
foreach ($archive in $manifest.sourceArchives) {
    if ($ordered.Name -notcontains ('LocalVoice/' + $archive.path)) { throw "Pinned source archive is missing from the allowlist: $($archive.path)" }
}
if ($Audit) {
    $entries = @(foreach ($member in $ordered) {
        [pscustomobject]@{ path=$member.Name; bytes=(Get-Item -LiteralPath $member.Source).Length; sha256=(Get-FileHash -LiteralPath $member.Source -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
    [pscustomobject]@{
        schemaVersion=1; archiveCreated=$false; fileCount=$entries.Count; totalBytes=[long](($entries | Measure-Object bytes -Sum).Sum)
        entryHashManifest='SOURCE-FILES.sha256'; pinnedSourceArchiveCount=@($manifest.sourceArchives).Count
        nativeSourceSnapshotCount=@($entries | Where-Object path -Match '^LocalVoice/third_party/native-source/.*\.zip$').Count
        entries=$entries
    } | ConvertTo-Json -Depth 5
    return
}
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
