param([switch]$SkipEngine,[string]$Jdk=$env:JAVA_HOME,[string]$Inno=$env:SNAGFOX_INNO)
if(!$Inno){$Inno="C:\Program Files (x86)\Inno Setup 6\ISCC.exe"}
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
foreach($required in 'vendor\electron\electron.exe','runtime\bin\java.exe','tools\ffmpeg.exe','build-tools\rcedit.exe',$Inno){if(!(Test-Path -LiteralPath $required)){throw "Missing offline build input: $required. See README.md."}}
if(!$SkipEngine){& .\build-engine.ps1 -Jdk $Jdk}
$stage=Join-Path $PSScriptRoot 'delivery\app'
New-Item -ItemType Directory $stage -Force | Out-Null
Copy-Item vendor\electron\* $stage -Recurse -Force
if(Test-Path "$stage\electron.exe"){Move-Item -LiteralPath "$stage\electron.exe" -Destination "$stage\Snagfox.exe" -Force}
$appRoot=Join-Path $stage 'resources\app'
New-Item -ItemType Directory $appRoot -Force | Out-Null
Copy-Item main.cjs,preload.cjs,menu-preload.cjs,package.json $appRoot -Force
foreach($folder in 'ui','runtime','tools','tests'){Copy-Item $folder $appRoot -Recurse -Force}
New-Item -ItemType Directory "$appRoot\engine" -Force | Out-Null
Copy-Item engine\snagfox-engine.jar "$appRoot\engine" -Force
Copy-Item engine\assets,engine\lib "$appRoot\engine" -Recurse -Force
Copy-Item THIRD_PARTY_NOTICES.md "$appRoot" -Force
& .\build-tools\rcedit.exe "$stage\Snagfox.exe" --set-icon ui\icon.ico --set-version-string ProductName Snagfox --set-version-string FileDescription 'Snagfox browser' --set-version-string CompanyName Snagfox --set-file-version 0.1.3.0 --set-product-version 0.1.3.0
if($LASTEXITCODE -ne 0){throw 'Executable branding failed'}
& $Inno installer\Snagfox.iss
if($LASTEXITCODE -ne 0){throw 'Installer compilation failed'}
Get-FileHash delivery\Snagfox-Setup.exe -Algorithm SHA256 | Format-List

