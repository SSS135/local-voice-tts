param([string]$JsonJar=$env:JSON_TEST_JAR)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { 'C:\Program Files\Android\Android Studio\jbr\bin' }
$classes = Join-Path $env:TEMP 'localvoice-storage-selftest'
$evidence = Join-Path $project 'build\verification'
New-Item -ItemType Directory -Force -Path $classes, $evidence | Out-Null
$source = Join-Path $project 'app\src\main\java\org\localvoice\tts'
$test = Join-Path $project 'app\src\test\java\org\localvoice\tts\StorageSelfTest.java'
if(!$JsonJar -or !(Test-Path -LiteralPath $JsonJar)){throw 'Supply -JsonJar for Android-compatible org.json used by current catalog metadata tests.'}
& (Join-Path $javaBin 'javac.exe') -encoding UTF-8 -cp $JsonJar -d $classes (Join-Path $source 'StorageMetrics.java') (Join-Path $source 'ModelFiles.java') (Join-Path $source 'DownloadFile.java') (Join-Path $source 'backend\SpeechBackend.java') (Join-Path $source 'backend\EdgeCatalogData.java') (Join-Path $source 'Catalog.java') $test
if ($LASTEXITCODE -ne 0) { throw 'Storage self-test compilation failed' }
& (Join-Path $javaBin 'java.exe') -ea -cp "$classes;$JsonJar" org.localvoice.tts.StorageSelfTest (Join-Path $project 'app\src\main\assets') | Tee-Object -FilePath (Join-Path $evidence 'storage-selfcheck.txt')
if ($LASTEXITCODE -ne 0) { throw 'Storage self-test failed' }
