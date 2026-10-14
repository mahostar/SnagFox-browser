param([string]$Jdk = $env:JAVA_HOME)
$ErrorActionPreference='Stop'
Set-Location $PSScriptRoot
if(!$Jdk){$Jdk='C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'}
if(!(Test-Path "$Jdk\bin\javac.exe")){throw "Missing JDK 17: $Jdk. Set JAVA_HOME or pass -Jdk."}
New-Item -ItemType Directory engine\classes -Force | Out-Null
$sources=Get-ChildItem engine\src -Recurse -Filter *.java | ForEach-Object { '"'+($_.FullName.Replace('\','/'))+'"' }
Set-Content -Path engine\sources.txt -Value $sources
& "$jdk\bin\javac.exe" -encoding UTF-8 -cp engine\lib\json.jar -d engine\classes '@engine/sources.txt'
if($LASTEXITCODE -ne 0){throw 'Java compilation failed'}
& "$jdk\bin\jar.exe" --create --file engine\snagfox-engine.jar -C engine\classes .

