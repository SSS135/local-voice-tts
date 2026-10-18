$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { 'C:\Program Files\Android\Android Studio\jbr\bin' }
$classes = Join-Path $env:TEMP 'localvoice-storage-selftest'
$evidence = Join-Path $project 'build\verification'
New-Item -ItemType Directory -Force -Path $classes, $evidence | Out-Null
$source = Join-Path $project 'app\src\main\java\org\localvoice\tts'
$test = Join-Path $project 'app\src\test\java\org\localvoice\tts\StorageSelfTest.java'
& (Join-Path $javaBin 'javac.exe') -d $classes (Join-Path $source 'ModelFiles.java') (Join-Path $source 'DownloadFile.java') (Join-Path $source 'Catalog.java') $test
if ($LASTEXITCODE -ne 0) { throw 'Storage self-test compilation failed' }
& (Join-Path $javaBin 'java.exe') -ea -cp $classes org.localvoice.tts.StorageSelfTest | Tee-Object -FilePath (Join-Path $evidence 'storage-selfcheck.txt')
if ($LASTEXITCODE -ne 0) { throw 'Storage self-test failed' }
