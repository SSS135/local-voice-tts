param([string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
if(!$JavaHome){$JavaHome=Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'}
$output=Join-Path $project 'build\verification'
$classes=Join-Path $output 'storage-publication-classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$source=Join-Path $project 'app\src\main\java\org\localvoice\tts'
$sources=@('StorageMetrics.java','ModelFiles.java','DownloadFile.java','SecureModelPackImporter.java','ModelStore.java')|ForEach-Object {Join-Path $source $_}
$fixtures=@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'storage-host') -Recurse -Filter '*.java' -File|ForEach-Object FullName)
& (Join-Path $JavaHome 'bin\javac.exe') --release 8 -encoding UTF-8 -d $classes $sources $fixtures
if($LASTEXITCODE-ne 0){throw 'Publication checks failed to compile'}
& (Join-Path $JavaHome 'bin\java.exe') -ea -cp $classes org.localvoice.tts.StoragePublicationCheck | Tee-Object -FilePath (Join-Path $output 'storage-publication.txt')
if($LASTEXITCODE-ne 0){throw 'Publication checks failed'}
