param([string]$JsonJar=$env:JSON_TEST_JAR,[string]$JavaHome=$env:JAVA_HOME,[string]$GradleCache='')
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
if(!$JavaHome){$JavaHome=Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'}
if(!$JsonJar -or !(Test-Path -LiteralPath $JsonJar)){throw 'Pass -JsonJar for Android-compatible org.json (test-only runtime).'}
if(!$GradleCache){
    foreach($cacheHome in @($env:GRADLE_USER_HOME,(Join-Path $env:USERPROFILE '.gradle'),(Join-Path (Split-Path $project -Parent) 'build-cache\gradle'))){
        if($cacheHome){$candidate=Join-Path $cacheHome 'caches\modules-2\files-2.1';if(Test-Path -LiteralPath (Join-Path $candidate 'com.squareup.okhttp3')){$GradleCache=$candidate;break}}
    }
}
if(!$GradleCache){throw 'Supply -GradleCache pointing at existing modules-2/files-2.1 dependencies.'}
$classes=Join-Path $project ('build\edge-catalog-repository\'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$android=Join-Path $env:LOCALAPPDATA 'Android\Sdk\platforms\android-36\android.jar'
$kotlin=@(Get-ChildItem (Join-Path $GradleCache 'org.jetbrains.kotlin\kotlin-stdlib\2.3.21') -Recurse -Filter '*.jar' -File)[0].FullName
$network=@(Get-ChildItem (Join-Path $GradleCache 'com.squareup.okhttp3'),(Join-Path $GradleCache 'com.squareup.okio') -Recurse -Filter '*.jar' -File | ForEach-Object FullName)
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach($aar in @(Get-ChildItem (Join-Path $GradleCache 'com.squareup.okhttp3') -Recurse -Filter '*.aar' -File)){
    $out=Join-Path $classes ($aar.BaseName+'.jar');$zip=[IO.Compression.ZipFile]::OpenRead($aar.FullName)
    try{[IO.Compression.ZipFileExtensions]::ExtractToFile($zip.GetEntry('classes.jar'),$out)}finally{$zip.Dispose()};$network+=$out
}
$classpath=(@($JsonJar,$android,$kotlin)+$network)-join [IO.Path]::PathSeparator
$source=Join-Path $project 'app\src\main\java\org\localvoice\tts'
$sources=@('backend\SpeechBackend.java','backend\EdgeRetry.java','backend\EdgeAudio.java','backend\EdgeProtocol.java','backend\EdgeGec.java','backend\EdgeBackend.java','backend\EdgeCatalogData.java','backend\EdgeCatalogClient.java','Catalog.java','EdgeCatalogRepository.java')|ForEach-Object{Join-Path $source $_}
& (Join-Path $JavaHome 'bin\javac.exe') --release 17 -encoding UTF-8 -cp $classpath -d $classes $sources (Join-Path $PSScriptRoot 'EdgeCatalogRepositoryCheck.java')
if($LASTEXITCODE -ne 0){throw 'Repository checks failed to compile'}
& (Join-Path $JavaHome 'bin\java.exe') --add-modules jdk.httpserver -ea -cp "$classes;$classpath" org.localvoice.tts.EdgeCatalogRepositoryCheck (Join-Path $project 'app\src\main\assets\edge-voices-snapshot.json') (Split-Path $classes -Parent)
if($LASTEXITCODE -ne 0){throw 'Repository checks failed'}
