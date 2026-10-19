param([string]$JavaHome=$env:JAVA_HOME,[string]$ManifestDirectory='', [string]$OutputDirectory='')
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$workspace=Split-Path $project -Parent
if(!$JavaHome){$JavaHome=Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'}
if(!$ManifestDirectory){$ManifestDirectory=Join-Path $workspace 'evidence\verification-profile\host-baseline'}
if(!$OutputDirectory){$OutputDirectory=Join-Path $workspace 'evidence\verification-profile\host-transfer'}
if(Test-Path -LiteralPath $OutputDirectory){throw 'Preserve evidence; choose a new -OutputDirectory.'}
$classes=Join-Path $OutputDirectory 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$source=Join-Path $project 'app\src\main\java\org\localvoice\tts'
$sources=@('StorageMetrics.java','ModelFiles.java','DownloadFile.java')|ForEach-Object {Join-Path $source $_}
& (Join-Path $JavaHome 'bin\javac.exe') --release 8 -encoding UTF-8 -d $classes $sources (Join-Path $project 'app\src\test\java\org\localvoice\tts\StorageTransferProfile.java')
if($LASTEXITCODE-ne 0){throw 'Transfer component profile compilation failed'}
foreach($dataset in @(@{Id='kokoro-en';Root='research\kokoro-extract\kokoro-int8-multi-lang-v1_0'},@{Id='supertonic-3';Root='research\supertonic-direct'})){
    $profileArgs=@((Join-Path $workspace $dataset.Root),(Join-Path $ManifestDirectory ($dataset.Id+'.tsv')),$dataset.Id,(Join-Path $OutputDirectory 'temporary-transfer.bin'))
    & (Join-Path $JavaHome 'bin\java.exe') -cp $classes org.localvoice.tts.StorageTransferProfile @profileArgs | Tee-Object -FilePath (Join-Path $OutputDirectory ($dataset.Id+'.jsonl'))
    if($LASTEXITCODE-ne 0){throw 'Transfer component profile failed'}
}
