param([string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
if(!$JavaHome){$JavaHome=Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'}
$output=Join-Path $project 'build\verification'
$classes=Join-Path $output 'storage-integrity-classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$source=Join-Path $project 'app\src\main\java\org\localvoice\tts'
$sources=@('StorageMetrics.java','ModelFiles.java','DownloadFile.java')|ForEach-Object {Join-Path $source $_}
& (Join-Path $JavaHome 'bin\javac.exe') --release 8 -encoding UTF-8 -d $classes $sources (Join-Path $project 'app\src\test\java\org\localvoice\tts\StorageIntegrityCheck.java')
if($LASTEXITCODE-ne 0){throw 'Storage integrity checks failed to compile'}
& (Join-Path $JavaHome 'bin\java.exe') -ea -cp $classes org.localvoice.tts.StorageIntegrityCheck | Tee-Object -FilePath (Join-Path $output 'storage-integrity.txt')
if($LASTEXITCODE-ne 0){throw 'Storage integrity checks failed'}
