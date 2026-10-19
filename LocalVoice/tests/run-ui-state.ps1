param([string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
if(!$JavaHome){$JavaHome='C:\Program Files\Android\Android Studio\jbr'}
$project=Split-Path $PSScriptRoot -Parent
$source=Join-Path $project 'app\src\main\java\org\localvoice\tts'
$classes=Join-Path $env:TEMP 'localvoice-ui-state-tests'
$evidence=Join-Path $project 'build\verification'
New-Item -ItemType Directory -Force -Path $classes,$evidence | Out-Null
& (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -d $classes (Join-Path $source 'VoiceFilter.java') (Join-Path $source 'PreviewSession.java') (Join-Path $PSScriptRoot 'VoiceFilterCheck.java')
if($LASTEXITCODE -ne 0){throw 'UI state check compilation failed'}
& (Join-Path $JavaHome 'bin\java.exe') -ea -cp $classes org.localvoice.tts.VoiceFilterCheck | Tee-Object -FilePath (Join-Path $evidence 'ui-state-checks.txt')
if($LASTEXITCODE -ne 0){throw 'UI state checks failed'}
