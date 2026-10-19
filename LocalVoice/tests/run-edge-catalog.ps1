param([string]$JsonJar=$env:JSON_TEST_JAR,[string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
if(!$JavaHome){$JavaHome=Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'}
if(!$JsonJar -or !(Test-Path -LiteralPath $JsonJar)){throw 'Pass -JsonJar for Android-compatible org.json (com.vaadin.external.google:android-json:0.0.20131108.vaadin1); production uses Android platform JSON.'}
$classes=Join-Path $project ('build\edge-catalog\'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$source=Join-Path $project 'app\src\main\java\org\localvoice\tts'
$sources=@('backend\SpeechBackend.java','backend\EdgeRetry.java','backend\EdgeProtocol.java','backend\EdgeCatalogData.java','Catalog.java') | ForEach-Object {Join-Path $source $_}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $JsonJar -d $classes $sources (Join-Path $PSScriptRoot 'EdgeCatalogCheck.java')
if($LASTEXITCODE -ne 0){throw 'Catalog checks failed to compile'}
& (Join-Path $JavaHome 'bin\java.exe') -ea -cp "$classes;$JsonJar" org.localvoice.tts.backend.EdgeCatalogCheck (Join-Path $project 'app\src\main\assets\edge-voices-snapshot.json') (Split-Path $classes -Parent)
if($LASTEXITCODE -ne 0){throw 'Catalog checks failed'}
