param([string]$JavaHome=$env:JAVA_HOME,[string]$JsonJar=$env:JSON_TEST_JAR,[string]$ArchivePath='')
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
if(!$JavaHome){$JavaHome=Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'}
if(!$JsonJar-or !(Test-Path -LiteralPath $JsonJar)){throw 'Pass -JsonJar with Android-compatible org.json used by the pinned catalog.'}
$output=Join-Path $project 'build\verification'
$classes=Join-Path $output 'model-pack-import-classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$source=Join-Path $project 'app\src\main\java\org\localvoice\tts'
$sources=@('StorageMetrics.java','ModelFiles.java','DownloadFile.java','SecureModelPackImporter.java','Catalog.java','backend\SpeechBackend.java','backend\EdgeCatalogData.java')|ForEach-Object {Join-Path $source $_}
& (Join-Path $JavaHome 'bin\javac.exe') --release 8 -encoding UTF-8 -cp $JsonJar -d $classes $sources (Join-Path $project 'app\src\test\java\org\localvoice\tts\ModelPackImportCheck.java') (Join-Path $project 'app\src\test\java\org\localvoice\tts\ActualModelPackCheck.java')
if($LASTEXITCODE-ne 0){throw 'Model-pack importer checks failed to compile'}
& (Join-Path $JavaHome 'bin\java.exe') -ea -cp "$classes;$JsonJar" org.localvoice.tts.ModelPackImportCheck | Tee-Object -FilePath (Join-Path $output 'model-pack-import.txt')
if($LASTEXITCODE-ne 0){throw 'Model-pack importer checks failed'}
if($ArchivePath){
    & (Join-Path $JavaHome 'bin\java.exe') -ea -cp "$classes;$JsonJar" org.localvoice.tts.ActualModelPackCheck $ArchivePath $output | Tee-Object -FilePath (Join-Path $output 'actual-model-pack-import.txt')
    if($LASTEXITCODE-ne 0){throw 'Actual pinned model archive failed import verification'}
}
