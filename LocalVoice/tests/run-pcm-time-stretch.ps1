param([string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
if(!$JavaHome){$JavaHome=Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'}
$classes=Join-Path $project ('build\pcm-time-stretch\'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$source=Join-Path $project 'app\src\main\java\org\localvoice\tts\backend'
$sources=@('SpeechBackend.java','Sonic.java','PcmTimeStretch.java')|ForEach-Object{Join-Path $source $_}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -d $classes $sources (Join-Path $PSScriptRoot 'PcmTimeStretchCheck.java')
if($LASTEXITCODE -ne 0){throw 'PCM time stretch checks failed to compile'}
& (Join-Path $JavaHome 'bin\java.exe') -ea -cp $classes org.localvoice.tts.backend.PcmTimeStretchCheck
if($LASTEXITCODE -ne 0){throw 'PCM time stretch checks failed'}
