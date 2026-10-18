param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
if (!$JavaHome) { $JavaHome = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr' }
$javac=Join-Path $JavaHome 'bin\javac.exe'
$java=Join-Path $JavaHome 'bin\java.exe'
if (!(Test-Path -LiteralPath $javac)) { throw 'Set JAVA_HOME or pass -JavaHome for a JDK 17 or newer.' }
$output=Join-Path $project 'build\edge-retry'
$classes=Join-Path $output ([Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$source=Join-Path $project 'app\src\main\java\org\localvoice\tts\backend'
$test=Join-Path $project 'app\src\test\java\org\localvoice\tts\backend\EdgeRetryContractTest.java'
& $javac --release 17 -encoding UTF-8 -d $classes (Join-Path $source 'SpeechBackend.java') (Join-Path $source 'EdgeRetry.java') (Join-Path $source 'EdgeAudio.java') $test
if ($LASTEXITCODE -ne 0) { throw 'Edge retry contract compilation failed' }
& $java -ea -cp $classes org.localvoice.tts.backend.EdgeRetryContractTest | Tee-Object -FilePath (Join-Path $output 'result.txt')
if ($LASTEXITCODE -ne 0) { throw 'Edge retry contract checks failed' }
