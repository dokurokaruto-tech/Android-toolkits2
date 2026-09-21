# Run only when Windows Firewall blocks the connection. Requires Administrator.
# No router port forwarding; public-network access is limited to Tailscale IPv4.
#Requires -RunAsAdministrator
$ErrorActionPreference = 'Stop'
$port = 3001
foreach ($name in @('config.json', 'config.local.json')) {
    $path = Join-Path $PSScriptRoot $name
    if (Test-Path $path) {
        $config = Get-Content -Raw -Encoding UTF8 $path | ConvertFrom-Json
        if ($null -ne $config.listen_port) { $port = [int]$config.listen_port }
    }
}
if ($port -lt 1 -or $port -gt 65535) { throw 'Invalid listen_port' }

$rules = @(
    @{ Name = 'AndroidToolkits-Agent-LAN'; Profile = 'Private'; Remote = 'LocalSubnet' },
    @{ Name = 'AndroidToolkits-Agent-Tailscale'; Profile = 'Any'; Remote = '100.64.0.0/10' }
)
foreach ($rule in $rules) {
    $existing = Get-NetFirewallRule -Name $rule.Name -ErrorAction SilentlyContinue
    if ($existing) { $existing | Remove-NetFirewallRule }
    New-NetFirewallRule -Name $rule.Name -DisplayName $rule.Name -Direction Inbound `
        -Action Allow -Protocol TCP -LocalPort $port -RemoteAddress $rule.Remote `
        -Profile $rule.Profile | Out-Null
}
Write-Host "Allowed agent TCP port $port for private LAN and Tailscale IPv4 only."
