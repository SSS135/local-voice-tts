# Host-only packaging checks. Never creates a delivery archive or reads private files.
$ErrorActionPreference = 'Stop'
$checkRoot = Split-Path $PSScriptRoot -Parent
$safeFixture = Join-Path $checkRoot 'README.md'
$null = . (Join-Path $checkRoot 'package-source.ps1') -ListOnly
$checks = 0
function Check([bool]$condition, [string]$message) {
    if (!$condition) { throw $message }
    $script:checks++
}
Check ($ordered.Count -gt 150) 'Incomplete source inventory'
Check (@($ordered.Name | Where-Object { $_.Contains([char]92) }).Count -eq 0) 'ZIP member has backslashes'
Check (@($manifest.sourceArchives).Count -eq 6) 'Expected six pinned upstream source archives'
Check (@($ordered.Name | Where-Object { $_ -match '^research/native-source/.*\.zip$' }).Count -eq 3) 'Native GPL source snapshots missing'
foreach ($required in @(
    'LocalVoice/docs/UI-DESIGN.md', 'LocalVoice/docs/EDGE-CATALOG.md', 'LocalVoice/docs/history/ENGLISH-POCKET-REVIEW.md',
    'LocalVoice/docs/history/VERIFICATION-PROFILE.md', 'LocalVoice/legal/THIRD_PARTY_SONIC_NOTICES.txt',
    'LocalVoice/fetch-runtime.ps1', 'AGENTS.md', 'README.md', 'research/licenses/ESPEAK-NG-COPYING',
    'LocalVoice/third_party/androidx-media3-sonic/Sonic.java',
    'LocalVoice/app/src/main/assets/licenses/SENTENCEPIECE-LICENSE.txt',
    'research/shtorm/REPRODUCE.md', 'research/shtorm/prepare_config.py',
    'research/pocket-en/REPRODUCE.md', 'research/pocket-en/frontend-proof/result.json')) {
    Check ($ordered.Name -contains $required) "Missing corresponding source: $required"
}
# Use an ordinary public README as the source for every rejection case. Only the
# proposed archive name is unsafe; no secret/model file is opened by these tests.
foreach ($blocked in @(
    'LocalVoice/signing/a.txt', 'LocalVoice/.git/config', 'LocalVoice/.aws/credentials',
    'LocalVoice/deps/a.py', 'LocalVoice/native-deps/a.py', 'LocalVoice/venv/a.py',
    'LocalVoice/.venv/a.py', 'LocalVoice/site-packages/a.py', 'LocalVoice/build/a.txt',
    'LocalVoice/downloads/a.txt', 'LocalVoice/models/a.txt',
    'LocalVoice/store-password.txt', 'LocalVoice/signing.local.ps1', 'LocalVoice/local.properties',
    'LocalVoice/private-vn-prefs-backup.xml', 'LocalVoice/.env', 'LocalVoice/.env.production',
    'LocalVoice/signing.properties', 'LocalVoice/REVIEW.md', 'LocalVoice/REVIEW-1.1.md',
    'research/runtime-model.zip', 'research/runtime.tar.gz', 'research/runtime.tar.zst', 'research/runtime.gz', 'research/model.safetensors',
    'research/model.onnx', 'research/tokenizer.model', 'research/weights.bin',
    'research/checkpoint.ckpt', 'research/audio.wav', 'research/key.jks',
    '../escape.txt', 'LocalVoice/../escape.txt', '/absolute.txt', 'C:/absolute.txt',
    'LocalVoice//ambiguous.txt', 'LocalVoice/./ambiguous.txt',
    'research/shtorm/lookbe-source.zip')) {
    $rejected = $false
    try { Add-MemberFile $safeFixture $blocked } catch { $rejected = $true }
    Check $rejected "Unsafe archive name accepted: $blocked"
}
Add-MemberFile $safeFixture 'LocalVoice\normalization-fixture.txt'
Check ($members[$members.Count - 1].Name -eq 'LocalVoice/normalization-fixture.txt') 'Windows member path not normalized'
$junctionFixture = Join-Path $workspace ('build\source-package-check-' + [Guid]::NewGuid().ToString('N'))
$publicTarget = Join-Path $junctionFixture 'public'
$junctionLink = Join-Path $junctionFixture 'link'
try {
    New-Item -ItemType Directory -Path $publicTarget -Force | Out-Null
    'Public harmless fixture' | Set-Content -LiteralPath (Join-Path $publicTarget 'fixture.txt') -Encoding ASCII
    New-Item -ItemType Junction -Path $junctionLink -Target $publicTarget -ErrorAction Stop | Out-Null
    $ancestorRejected = $false
    try { Add-MemberFile (Join-Path $junctionLink 'fixture.txt') 'LocalVoice/tests/junction-fixture.txt' }
    catch { $ancestorRejected = $_.Exception.Message -like 'Reparse-point source ancestor:*' }
    Check $ancestorRejected 'Regular leaf beneath a junction was accepted'
} finally {
    # No recursive deletion: remove only this run's exact link/file/empty dirs.
    if (Test-Path -LiteralPath $junctionLink) { [IO.Directory]::Delete($junctionLink) }
    $publicFile = Join-Path $publicTarget 'fixture.txt'
    if (Test-Path -LiteralPath $publicFile) { [IO.File]::Delete($publicFile) }
    if (Test-Path -LiteralPath $publicTarget) { [IO.Directory]::Delete($publicTarget) }
    if (Test-Path -LiteralPath $junctionFixture) { [IO.Directory]::Delete($junctionFixture) }
}
Write-Output "PASS $checks source-package checks; no ZIP created; harmless public fixtures used for rejection probes"
