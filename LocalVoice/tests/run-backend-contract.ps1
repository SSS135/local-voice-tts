param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$GradleCache = "",
    [string]$KotlinStdlib = ""
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
if (!$JavaHome) { $JavaHome = 'C:\Program Files\Android\Android Studio\jbr' }
if (!$AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if (!$AndroidSdk -and (Test-Path -LiteralPath (Join-Path $project 'local.properties'))) {
    $sdkSetting = Get-Content (Join-Path $project 'local.properties') | Where-Object { $_ -match '^sdk.dir=' }
    $AndroidSdk = $sdkSetting -replace '^sdk.dir=', ''
}
if (!$AndroidSdk -and $env:LOCALAPPDATA) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (!$GradleCache) {
    $cacheHomes = @($env:GRADLE_USER_HOME, (Join-Path $env:USERPROFILE '.gradle'), (Join-Path (Split-Path $project -Parent) 'build-cache\gradle'))
    foreach ($cacheHome in $cacheHomes) {
        if ($cacheHome) {
            $candidate = Join-Path $cacheHome 'caches\modules-2\files-2.1'
            if (Test-Path -LiteralPath (Join-Path $candidate 'com.squareup.okhttp3')) { $GradleCache = $candidate; break }
        }
    }
    if (!$GradleCache) { throw 'Build the Android project to populate dependencies, or supply -GradleCache pointing at caches/modules-2/files-2.1.' }
}
if (!$KotlinStdlib) {
    $cachedKotlin = @(Get-ChildItem (Join-Path $GradleCache 'org.jetbrains.kotlin\kotlin-stdlib\2.3.21') -Recurse -Filter '*.jar' -File -ErrorAction SilentlyContinue)
    if ($cachedKotlin.Count) { $KotlinStdlib = $cachedKotlin[0].FullName }
    else { $KotlinStdlib = Join-Path $env:ProgramFiles 'Android\Android Studio\plugins\Kotlin\kotlinc\lib\kotlin-stdlib.jar' }
}
$javac = Join-Path $JavaHome 'bin\javac.exe'
$java = Join-Path $JavaHome 'bin\java.exe'
$androidJar = Join-Path $AndroidSdk 'platforms\android-36\android.jar'
foreach ($required in @($javac, $java, $androidJar, $KotlinStdlib)) {
    if (!(Test-Path -LiteralPath $required)) { throw "Missing test compile prerequisite: $required" }
}
$output = Join-Path $project 'build\backend-contract'
$classes = Join-Path $output ([Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $classes | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$sherpa = Join-Path $classes 'sherpa.jar'
$archive = [IO.Compression.ZipFile]::OpenRead((Join-Path $project 'app\libs\sherpa-onnx-1.13.8.aar'))
try { [IO.Compression.ZipFileExtensions]::ExtractToFile($archive.GetEntry('classes.jar'), $sherpa) }
finally { $archive.Dispose() }
$networkJars = @(Get-ChildItem (Join-Path $GradleCache 'com.squareup.okhttp3'), (Join-Path $GradleCache 'com.squareup.okio') -Recurse -Filter '*.jar' -File | ForEach-Object FullName)
foreach ($aar in @(Get-ChildItem (Join-Path $GradleCache 'com.squareup.okhttp3') -Recurse -Filter '*.aar' -File)) {
    $extracted = Join-Path $classes ($aar.BaseName + '.jar')
    $networkArchive = [IO.Compression.ZipFile]::OpenRead($aar.FullName)
    try { [IO.Compression.ZipFileExtensions]::ExtractToFile($networkArchive.GetEntry('classes.jar'), $extracted) }
    finally { $networkArchive.Dispose() }
    $networkJars += $extracted
}
$classpath = (@($androidJar, $sherpa, $KotlinStdlib) + $networkJars) -join [IO.Path]::PathSeparator
$sources = @(Get-ChildItem (Join-Path $project 'app\src\main\java\org\localvoice\tts\backend') -Filter '*.java' | ForEach-Object FullName)
$sources += Join-Path $project 'app\src\test\java\org\localvoice\tts\backend\BackendContractTest.java'
$sources += Join-Path $project 'tests\EdgeProtocolCheck.java'
& $javac -encoding UTF-8 --release 17 -cp $classpath -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Backend contract compilation failed' }
$log = Join-Path $output 'result.txt'
& $java -cp ($classes + [IO.Path]::PathSeparator + $classpath) org.localvoice.tts.backend.BackendContractTest | Tee-Object -FilePath $log
if ($LASTEXITCODE -ne 0) { throw 'Backend contract tests failed' }
& $java -ea -cp ($classes + [IO.Path]::PathSeparator + $classpath) org.localvoice.tts.backend.EdgeProtocolCheck | Tee-Object -FilePath $log -Append
if ($LASTEXITCODE -ne 0) { throw 'Edge protocol tests failed' }
Write-Output "Evidence: $log"
