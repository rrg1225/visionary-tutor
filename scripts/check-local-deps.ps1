param(
    [int]$MysqlPort = 3306,
    [int]$RedisPort = 6379,
    [int]$ChromaPort = 8000
)

$ErrorActionPreference = 'Stop'
$checks = @(
    @{ Name = 'MySQL'; Port = $MysqlPort },
    @{ Name = 'Redis'; Port = $RedisPort },
    @{ Name = 'Chroma'; Port = $ChromaPort }
)
$failed = $false

foreach ($check in $checks) {
    $open = Test-NetConnection -ComputerName 127.0.0.1 -Port $check.Port -InformationLevel Quiet
    if ($open) {
        Write-Host "[OK] $($check.Name) 127.0.0.1:$($check.Port)"
    } else {
        Write-Host "[FAIL] $($check.Name) 127.0.0.1:$($check.Port)"
        $failed = $true
    }
}

try {
    $heartbeat = Invoke-RestMethod -Uri "http://127.0.0.1:$ChromaPort/api/v2/heartbeat" -TimeoutSec 5
    if ($null -ne $heartbeat) {
        Write-Host '[OK] Chroma v2 heartbeat'
    }
} catch {
    Write-Host "[FAIL] Chroma heartbeat: $($_.Exception.Message)"
    $failed = $true
}

if ($failed) { exit 1 }
Write-Host 'All local dependencies are reachable.'

