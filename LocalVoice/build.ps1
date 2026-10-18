param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$GradleExecutable = '',
    [string]$GradleUserHome = '',
    [string[]]$Tasks = @(':app:assembleDebug', ':probe:assembleDebug'),
    [switch]$Offline,
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
if (!$JavaHome) {
    $studioJdk = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    if (Test-Path -LiteralPath (Join-Path $studioJdk 'bin\javac.exe')) { $JavaHome = $studioJdk }
    else {
        $compiler = Get-Command javac.exe -ErrorAction SilentlyContinue
        if ($compiler) { $JavaHome = Split-Path (Split-Path $compiler.Source -Parent) -Parent }
    }
}
if (!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe'))) {
    throw 'Install a JDK 17 or newer and set JAVA_HOME, or supply -JavaHome.'
}
if (!$AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
$localProperties = Join-Path $PSScriptRoot 'local.properties'
if (!$AndroidSdk -and (Test-Path -LiteralPath $localProperties)) {
    $existingSdk = @(Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^sdk\.dir=' })
    if ($existingSdk.Count) { $AndroidSdk = ($existingSdk[-1] -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\\\', '\' }
}
if (!$AndroidSdk -and $env:LOCALAPPDATA) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (!$AndroidSdk -or !(Test-Path -LiteralPath (Join-Path $AndroidSdk 'platforms\android-36\android.jar'))) {
    throw 'Install Android SDK Platform 36 and build tools with Android Studio/SDK Manager, then set ANDROID_HOME or supply -AndroidSdk.'
}
if (Test-Path -LiteralPath $localProperties) {
    $sdkLine = @(Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^sdk\.dir=' })
    if ($sdkLine.Count) {
        $configuredSdk = ($sdkLine[-1] -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\\\', '\'
        if ([IO.Path]::GetFullPath($configuredSdk).TrimEnd('\','/') -ne [IO.Path]::GetFullPath($AndroidSdk).TrimEnd('\','/')) {
            throw 'local.properties points at a different SDK. Update/remove its sdk.dir entry or supply the same -AndroidSdk path.'
        }
    }
}
if (!$GradleExecutable) { $GradleExecutable = Join-Path $PSScriptRoot 'gradlew.bat' }
if (!(Test-Path -LiteralPath $GradleExecutable)) {
    $installed = Get-Command $GradleExecutable -ErrorAction SilentlyContinue
    if (!$installed) { throw 'Gradle wrapper/command missing. Supply -GradleExecutable with Gradle 9.5.1.' }
    $GradleExecutable = $installed.Source
}
if (!$Tasks.Count) { throw 'Supply at least one Gradle task.' }
Write-Output "Java: $JavaHome"
Write-Output "Android SDK: $AndroidSdk"
Write-Output "Gradle: $GradleExecutable"
if ($CheckOnly) { Write-Output 'Prerequisite paths checked; Gradle was not run.'; return }
$saved = @{}
foreach ($name in @('JAVA_HOME','ANDROID_HOME','ANDROID_SDK_ROOT','GRADLE_USER_HOME')) { $saved[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
try {
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $AndroidSdk
    $env:ANDROID_SDK_ROOT = $AndroidSdk
    if ($GradleUserHome) { $env:GRADLE_USER_HOME = $GradleUserHome }
    $arguments = @('--max-workers=2')
    if ($Offline) { $arguments += '--offline' }
    Push-Location $PSScriptRoot
    try { & $GradleExecutable @arguments @Tasks; $code = $LASTEXITCODE } finally { Pop-Location }
    if ($code -ne 0) { throw "Gradle failed with exit code $code" }
} finally {
    foreach ($name in $saved.Keys) {
        if ($null -eq $saved[$name]) { Remove-Item -LiteralPath ('Env:' + $name) -ErrorAction SilentlyContinue }
        else { Set-Item -LiteralPath ('Env:' + $name) -Value $saved[$name] }
    }
}
