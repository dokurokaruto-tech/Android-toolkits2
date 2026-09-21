# Called only after setup-tts.bat receives Y. No machine-wide install or reboot.
param([switch]$Approved)
$ErrorActionPreference = 'Stop'

if (-not $Approved) {
    Write-Host '[ERROR] Run setup-tts.bat and approve Python installation first.'
    exit 1
}
if (-not [Environment]::Is64BitOperatingSystem) {
    Write-Host '[ERROR] A 64-bit Windows installation is required.'
    exit 1
}

# 3.12.10 is the final regular 3.12 release with official Windows installers.
$version = '3.12.10'
$url = "https://www.python.org/ftp/python/$version/python-$version-amd64.exe"
$successCode = 0
$rebootRequiredCode = 3010
$directory = Join-Path ([IO.Path]::GetTempPath()) ("toolkits-python-" + [Guid]::NewGuid().ToString('N'))
$target = Join-Path $env:LOCALAPPDATA 'Programs\Python\Python312'
$result = 1

try {
    New-Item -ItemType Directory -Path $directory | Out-Null
    $installer = Join-Path $directory 'python-installer.exe'
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Write-Host "[INFO] Downloading $url"
    Invoke-WebRequest -Uri $url -OutFile $installer -UseBasicParsing -TimeoutSec 300

    $signature = Get-AuthenticodeSignature -LiteralPath $installer
    if ($signature.Status -ne 'Valid' -or $null -eq $signature.SignerCertificate -or
        $signature.SignerCertificate.Subject -notmatch '(^|,\s*)O=Python Software Foundation(,|$)') {
        throw 'Python installer signature verification failed. Nothing was executed.'
    }

    # Retain the installer log, but remove downloaded executable files in finally.
    $log = Join-Path ([IO.Path]::GetTempPath()) ("toolkits-python312-" + [Guid]::NewGuid().ToString('N') + '.log')
    Write-Host "[INFO] Installing Python $version for the current user. Log: $log"
    $arguments = @('/quiet', '/norestart', '/log', ('"' + $log + '"'),
        'InstallAllUsers=0', 'Include_launcher=1', 'InstallLauncherAllUsers=0',
        'Include_pip=1', 'Include_test=0', 'PrependPath=0', ('TargetDir="' + $target + '"'))
    $process = Start-Process -FilePath $installer -ArgumentList $arguments -Wait -PassThru
    if ($process.ExitCode -ne $successCode -and $process.ExitCode -ne $rebootRequiredCode) {
        throw "Python installer exited with code $($process.ExitCode). See $log"
    }
    if ($process.ExitCode -eq $rebootRequiredCode) {
        Write-Host '[INFO] Windows requested a restart. If setup cannot continue, restart and rerun setup-tts.bat.'
    }
    $result = 0
} catch {
    Write-Host "[ERROR] $($_.Exception.Message)"
    Write-Host '[INFO] Check the network / Windows installation policy, or install Python 3.12 manually.'
} finally {
    if (Test-Path -LiteralPath $directory) {
        Remove-Item -LiteralPath $directory -Recurse -Force -ErrorAction SilentlyContinue
    }
}
exit $result
