param([string]$JavaHome=$env:JAVA_HOME,[ValidateSet('baseline','cache')][string]$Mode='baseline',[string]$OutputDirectory='')
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$workspace=Split-Path $project -Parent
if(!$JavaHome){$JavaHome=Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'}
$output=if($OutputDirectory){$OutputDirectory}else{Join-Path $workspace ('evidence\verification-profile\host-'+$Mode)}
if(Test-Path -LiteralPath $output){throw 'Output directory already exists; preserve before/after evidence and choose a new -OutputDirectory.'}
$classes=Join-Path $output 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$source=Join-Path $project 'app\src\main\java\org\localvoice\tts'
& (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -d $classes (Join-Path $source 'StorageMetrics.java') (Join-Path $source 'ModelFiles.java') (Join-Path $project 'app\src\test\java\org\localvoice\tts\StorageHashProfile.java')
if($LASTEXITCODE-ne 0){throw 'Storage hash profile compilation failed'}
$catalog=Get-Content -LiteralPath (Join-Path $project 'app\src\main\resources\org\localvoice\tts\models.json') -Raw -Encoding UTF8|ConvertFrom-Json
$datasets=@(
    @{Id='kokoro-en';Root=(Join-Path $workspace 'research\kokoro-extract\kokoro-int8-multi-lang-v1_0')},
    @{Id='supertonic-3';Root=(Join-Path $workspace 'research\supertonic-direct')}
)
$license=Join-Path $datasets[1].Root 'LICENSE-OpenRAIL-M.txt'
if(!(Test-Path -LiteralPath $license)){Copy-Item -LiteralPath (Join-Path $workspace 'research\licenses\SUPERTONIC-UPSTREAM-LICENSE') -Destination $license}
foreach($dataset in $datasets){
    $manifest=Join-Path $output ($dataset.Id+'.tsv')
    $files=@($catalog.models|Where-Object {$_.id-ceq $dataset.Id}|ForEach-Object {$_.files})
    $entries=@($files|ForEach-Object {$_.path+"`t"+$_.bytes+"`t"+$_.sha256})
    if(!$entries.Count){throw 'No pinned files parsed'}
    $entries|Set-Content -LiteralPath $manifest -Encoding UTF8
    $profileArgs=@($dataset.Root,$manifest,$dataset.Id)
    if($Mode-eq 'cache'){$profileArgs+='cache'}
    & (Join-Path $JavaHome 'bin\java.exe') -cp $classes org.localvoice.tts.StorageHashProfile @profileArgs | Tee-Object -FilePath (Join-Path $output ($dataset.Id+'.jsonl'))
    if($LASTEXITCODE-ne 0){throw 'Storage profile dataset verification failed'}
}
