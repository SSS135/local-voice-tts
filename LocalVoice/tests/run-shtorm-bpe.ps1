param([string]$JsonJar=$env:JSON_TEST_JAR,[string]$JavaHome=$env:JAVA_HOME,[string]$Research='')
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
if(!$JavaHome){$JavaHome='C:\Program Files\Android\Android Studio\jbr'}
if(!$JsonJar){$JsonJar=Join-Path (Split-Path $project -Parent) 'downloads\android-json-test.jar'}
if(!$Research){$Research=Join-Path (Split-Path $project -Parent) 'research\shtorm'}
if(!(Test-Path -LiteralPath $JsonJar)){throw 'Pass -JsonJar pointing at the Android-compatible org.json test jar'}
$classes=Join-Path $project 'build\shtorm-bpe-checks'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$source=Join-Path $project 'app\src\main\java\org\localvoice\tts\backend\ShtormBpeFrontend.java'
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $JsonJar -d $classes $source (Join-Path $PSScriptRoot 'ShtormBpeCheck.java')
if($LASTEXITCODE -ne 0){throw 'Shtorm BPE check compilation failed'}
& (Join-Path $JavaHome 'bin\java.exe') -ea -cp "$classes;$JsonJar" org.localvoice.tts.backend.ShtormBpeCheck (Join-Path $Research 'onnx') (Join-Path $PSScriptRoot 'fixtures\shtorm-tokenizer-parity.json') | Tee-Object -FilePath (Join-Path $classes 'result.txt')
if($LASTEXITCODE -ne 0){throw 'Shtorm BPE parity checks failed'}
