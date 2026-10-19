# Builds build/cli/neutts-cli.dex (app classes + NeuTtsCli) and copies libneutts_jni.so/libonnxruntime.so for one ABI.
# Prerequisite: .\gradlew.bat :app:assembleDebug. Usage: .\tools\models\neutts\build-cli.ps1 [-Abi arm64-v8a|x86_64]
param([string]$Abi = 'arm64-v8a')
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..\..')
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$androidJar = Join-Path $sdk 'platforms\android-36\android.jar'
$d8 = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object Name | Select-Object -Last 1 | ForEach-Object { Join-Path $_.FullName 'd8.bat' }
$classes = Get-ChildItem (Join-Path $root 'app\build\intermediates\javac\debug') -Directory | ForEach-Object { Join-Path $_.FullName 'classes' } | Where-Object { Test-Path $_ } | Select-Object -First 1
$out = Join-Path $PSScriptRoot "build\cli\$Abi"
New-Item -ItemType Directory -Force -Path (Join-Path $out 'classes') | Out-Null
& javac -nowarn -d (Join-Path $out 'classes') -cp "$classes;$androidJar" --release 17 (Join-Path $PSScriptRoot 'cli\NeuTtsCli.java')
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
$appJar = Join-Path $out 'app-classes.jar'; $cliJar = Join-Path $out 'cli-classes.jar'
& jar --create --file $appJar -C $classes .
& jar --create --file $cliJar -C (Join-Path $out 'classes') .
& $d8 --release --min-api 26 --lib $androidJar --output $out $appJar $cliJar 2>&1 | Where-Object { $_ -notmatch 'Warning|Missing class|referenced from' }
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
Move-Item -Force (Join-Path $out 'classes.dex') (Join-Path $out 'neutts-cli.dex')
$lib = Get-ChildItem -Recurse (Join-Path $root 'app\build\intermediates\stripped_native_libs\debug') -Filter libneutts_jni.so | Where-Object { $_.FullName -match [regex]::Escape("\$Abi\") } | Select-Object -First 1
Copy-Item -Force $lib.FullName $out
Copy-Item -Force (Join-Path $lib.DirectoryName 'libonnxruntime.so') $out
Write-Output "Built $out"
