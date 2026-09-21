# Called only after setup-tts.bat receives Y. Installs into the project, not Windows.
param(
    [switch]$Approved,
    [string]$ArchivePath
)
$ErrorActionPreference = 'Stop'

if (-not $Approved) {
    Write-Host '[ERROR] Run setup-tts.bat and approve SoX installation first.'
    exit 1
}

$version = '14.4.2'
# SHA256 published in Microsoft's ChrisBagwell.SoX 14.4.2 WinGet manifest:
# https://github.com/microsoft/winget-pkgs/blob/master/manifests/c/ChrisBagwell/SoX/14.4.2/ChrisBagwell.SoX.installer.yaml
$sha256 = '8072CC147CF1A3B3713B8B97D6844BB9389E211AB9E1101E432193FAD6AE6662'
$tools = Join-Path (Split-Path -Parent $PSScriptRoot) '.tools'
$temporary = Join-Path $tools ('sox-download-' + [Guid]::NewGuid().ToString('N'))
$destination = Join-Path $tools 'sox'
$backup = Join-Path $tools ('sox-backup-' + [Guid]::NewGuid().ToString('N'))
$result = 1
$python = Join-Path (Split-Path -Parent $PSScriptRoot) '.venv-tts\Scripts\python.exe'
$downloadLog = Join-Path $tools 'sox-download.log'

try {
    New-Item -ItemType Directory -Path $temporary -Force | Out-Null
    $archive = Join-Path $temporary 'sox.zip'
    if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
        throw 'TTS Python was not found. Run setup-tts.bat first.'
    }
    $helper = Join-Path $PSScriptRoot 'sox_download.py'
    $arguments = @($helper, '--output', $archive, '--log', $downloadLog)
    if ($ArchivePath) { $arguments += @('--archive', $ArchivePath) }
    & $python @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "SoX download/verification failed. Diagnostics: $downloadLog"
    }
    # Verify again at the execution boundary; no mirror can override the pinned hash.
    $actualHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
    if ($actualHash -ne $sha256) {
        throw "SoX SHA256 mismatch: expected $sha256, received $actualHash. Nothing was executed."
    }
    $extracted = Join-Path $temporary 'extracted'
    Expand-Archive -LiteralPath $archive -DestinationPath $extracted
    $source = Join-Path $extracted "sox-$version"
    $executable = Join-Path $source 'sox.exe'
    if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) {
        throw 'The verified archive does not contain sox.exe.'
    }
    & $executable --version
    if ($LASTEXITCODE -ne 0) { throw 'The downloaded SoX executable could not run.' }

    # Preserve all bundled DLLs. Keep the old installation until the new one is verified.
    if (Test-Path -LiteralPath $destination) {
        Move-Item -LiteralPath $destination -Destination $backup
    }
    try {
        Move-Item -LiteralPath $source -Destination $destination
    } catch {
        if ((Test-Path -LiteralPath $backup) -and -not (Test-Path -LiteralPath $destination)) {
            Move-Item -LiteralPath $backup -Destination $destination
        }
        throw
    }
    if (Test-Path -LiteralPath $backup) {
        Remove-Item -LiteralPath $backup -Recurse -Force -ErrorAction SilentlyContinue
    }
    Write-Host "[OK] SoX installed in $destination"
    Write-Host '[INFO] No global PATH change or Windows restart is needed.'
    $result = 0
} catch {
    Write-Host "[ERROR] $($_.Exception.Message)"
    Write-Host "[INFO] Download details, when available: $downloadLog"
    Write-Host '[INFO] Browser-downloaded ZIPs can be supplied using -ArchivePath; SHA256 verification remains mandatory.'
    Write-Host '[INFO] For file-in-use errors, close active TTS processes and rerun setup-tts.bat.'
} finally {
    if (Test-Path -LiteralPath $temporary) {
        Remove-Item -LiteralPath $temporary -Recurse -Force -ErrorAction SilentlyContinue
    }
}
exit $result
