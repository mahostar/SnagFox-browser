# Package already provisioned offline inputs; never fetch dependencies.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$inputFolders = @('vendor\electron', 'runtime', 'tools', 'build-tools')
$requiredFiles = @(
    'vendor\electron\electron.exe',
    'vendor\electron\LICENSE',
    'vendor\electron\LICENSES.chromium.html',
    'runtime\bin\java.exe',
    'tools\ffmpeg.exe',
    'tools\FFmpeg-COPYING.txt',
    'build-tools\rcedit.exe',
    'THIRD_PARTY_NOTICES.md'
)
foreach ($relativePath in $requiredFiles) {
    if (!(Test-Path -LiteralPath (Join-Path $projectRoot $relativePath) -PathType Leaf)) {
        throw "Missing offline build input: $relativePath. Provision the matching inputs before packaging."
    }
}
if (!(Test-Path -LiteralPath (Join-Path $projectRoot 'runtime\legal') -PathType Container)) {
    throw 'Missing runtime\legal license directory.'
}

$deliveryPath = Join-Path $projectRoot 'delivery'
New-Item -ItemType Directory -Path $deliveryPath -Force | Out-Null
$deliveryPath = (Resolve-Path -LiteralPath $deliveryPath).Path
$stagePath = Join-Path $deliveryPath ('build-inputs-stage-' + [Guid]::NewGuid().ToString('N'))
$archivePath = Join-Path $deliveryPath 'Snagfox-Build-Inputs.zip'
New-Item -ItemType Directory -Path $stagePath | Out-Null
try {
    New-Item -ItemType Directory -Path (Join-Path $stagePath 'vendor') | Out-Null
    foreach ($relativePath in $inputFolders) {
        $sourcePath = Join-Path $projectRoot $relativePath
        $parentPath = Split-Path -Path $relativePath -Parent
        $targetParent = if ($parentPath) { Join-Path $stagePath $parentPath } else { $stagePath }
        Copy-Item -LiteralPath $sourcePath -Destination $targetParent -Recurse -Force
    }
    Copy-Item -LiteralPath (Join-Path $projectRoot 'THIRD_PARTY_NOTICES.md') -Destination $stagePath

    # Compress-Archive skips Hidden entries. Clear that flag only on staged
    # copies so all files covered by the manifest are included in the zip.
    Get-ChildItem -LiteralPath $stagePath -Recurse -Force | ForEach-Object {
        if ($_.Attributes -band [IO.FileAttributes]::Hidden) {
            $_.Attributes = $_.Attributes -band (-bnot [IO.FileAttributes]::Hidden)
        }
    }
    $manifestLines = Get-ChildItem -LiteralPath $stagePath -File -Recurse -Force |
        Sort-Object FullName | ForEach-Object {
            $relativeName = $_.FullName.Substring($stagePath.Length + 1).Replace('\', '/')
            $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            "$hash  $relativeName"
        }
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllLines((Join-Path $stagePath 'BUILD_INPUTS_SHA256.txt'), [string[]]$manifestLines, $utf8)

    Compress-Archive -Path (Join-Path $stagePath '*') -DestinationPath $archivePath -CompressionLevel Optimal -Force
    $archiveHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($archivePath + '.sha256', "$archiveHash  Snagfox-Build-Inputs.zip`n", $utf8)
    Write-Output "Prepared $archivePath"
    Write-Output "SHA256 $archiveHash"
} finally {
    # Delete only this run's exact staging directory inside delivery.
    $resolvedStagePath = (Resolve-Path -LiteralPath $stagePath).Path
    $allowedPrefix = $deliveryPath.TrimEnd('\') + '\build-inputs-stage-'
    if (!$resolvedStagePath.StartsWith($allowedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove an unexpected staging path: $resolvedStagePath"
    }
    Remove-Item -LiteralPath $resolvedStagePath -Recurse -Force
}