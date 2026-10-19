param([string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
if(!$JavaHome){$JavaHome=Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'}
$output=Join-Path $project 'build\verification'
$classes=Join-Path $output 'shtorm-text-classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$source=Join-Path $project 'app\src\main\java\org\localvoice\tts\backend'
& (Join-Path $JavaHome 'bin\javac.exe') --release 8 -encoding UTF-8 -d $classes (Join-Path $source 'RussianCardinals.java') (Join-Path $source 'PocketPrompt.java') (Join-Path $PSScriptRoot 'ShtormTextCheck.java')
if($LASTEXITCODE-ne 0){throw 'Shtorm text checks failed to compile'}
& (Join-Path $JavaHome 'bin\java.exe') -ea -cp $classes org.localvoice.tts.backend.ShtormTextCheck | Tee-Object -FilePath (Join-Path $output 'shtorm-text.txt')
if($LASTEXITCODE-ne 0){throw 'Shtorm text checks failed'}
