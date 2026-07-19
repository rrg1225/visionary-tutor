# VisionaryTutor one-click local stack bootstrap
# Order: MySQL/Redis check -> Chroma (+ heartbeat wait) -> Spring Boot -> Vue -> RAG smoke
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$mysqlHost = if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { "127.0.0.1" }
$mysqlPort = if ($env:MYSQL_PORT) { [int]$env:MYSQL_PORT } else { 3306 }
$redisHost = if ($env:REDIS_HOST) { $env:REDIS_HOST } else { "127.0.0.1" }
$redisPort = if ($env:REDIS_PORT) { [int]$env:REDIS_PORT } else { 6379 }
$chromaHost = if ($env:CHROMA_HOST) { $env:CHROMA_HOST } else { "127.0.0.1" }
$chromaPort = if ($env:CHROMA_PORT) { [int]$env:CHROMA_PORT } else { 8000 }
$backendPort = if ($env:BACKEND_PORT) { [int]$env:BACKEND_PORT } else { 8080 }
$frontendPort = if ($env:FRONTEND_PORT) { [int]$env:FRONTEND_PORT } else { 5173 }

function Test-TcpPort {
    param([string]$HostName, [int]$Port, [int]$TimeoutMs = 2500)
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        $ok = $async.AsyncWaitHandle.WaitOne($TimeoutMs, $false)
        if ($ok -and $client.Connected) {
            $client.EndConnect($async)
            $client.Close()
            return $true
        }
        $client.Close()
        return $false
    } catch {
        return $false
    }
}

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Resolve-Maven {
    $wrapper = Join-Path $Root "mvnw.cmd"
    if (Test-Path -LiteralPath $wrapper) { return $wrapper }
    $cmd = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if (-not $cmd) {
        $cmd = Get-Command mvn -ErrorAction SilentlyContinue
    }
    if ($cmd) { return $cmd.Source }
    throw "Maven Wrapper is missing and Maven was not found on PATH."
}

function Test-ChromaHeartbeat {
    try {
        $uri = "http://${chromaHost}:${chromaPort}/api/v2/heartbeat"
        $response = Invoke-WebRequest -Uri $uri -UseBasicParsing -TimeoutSec 3
        return ($response.StatusCode -eq 200)
    } catch {
        return $false
    }
}

function Wait-BackendHealth {
    param([int]$MaxSeconds = 120)
    $deadline = (Get-Date).AddSeconds($MaxSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:${backendPort}/api/health" -TimeoutSec 5
            if ($health.status -eq "UP") {
                return $health
            }
        } catch {
            # keep waiting
        }
        Start-Sleep -Seconds 2
    }
    throw "Backend did not become healthy within ${MaxSeconds}s on port ${backendPort}"
}

Write-Host "VisionaryTutor / start-all.ps1"
Write-Host "Project root: $Root"

Write-Step "Checking MySQL (${mysqlHost}:${mysqlPort})"
if (Test-TcpPort -HostName $mysqlHost -Port $mysqlPort) {
    Write-Host "[OK] MySQL port is reachable." -ForegroundColor Green
} else {
    Write-Host "[WARN] MySQL is not reachable. Start MySQL 8 and ensure database visionary_tutor exists." -ForegroundColor Yellow
}

Write-Step "Checking Redis (${redisHost}:${redisPort})"
if (Test-TcpPort -HostName $redisHost -Port $redisPort) {
    Write-Host "[OK] Redis port is reachable." -ForegroundColor Green
} else {
    Write-Host "[WARN] Redis is not reachable. Guest quota and remediation progress need Redis." -ForegroundColor Yellow
}

Write-Step "Starting Chroma V2 (${chromaHost}:${chromaPort})"
if (Test-ChromaHeartbeat) {
    Write-Host "[OK] Chroma heartbeat already healthy." -ForegroundColor Green
} else {
    $chromaScript = Join-Path $Root "ai_engine\scripts\start_chroma.ps1"
    if (-not (Test-Path $chromaScript)) {
        throw "Missing Chroma script: $chromaScript"
    }
    Start-Process powershell -ArgumentList @(
        "-NoExit",
        "-ExecutionPolicy", "Bypass",
        "-File", $chromaScript
    ) | Out-Null
    Write-Host "Waiting for Chroma heartbeat (up to 60s)..." -ForegroundColor Yellow
    $heartbeatOk = $false
    for ($i = 0; $i -lt 30; $i++) {
        if (Test-ChromaHeartbeat) {
            $heartbeatOk = $true
            break
        }
        Start-Sleep -Seconds 2
    }
    if (-not $heartbeatOk) {
        throw "Chroma heartbeat not ready. Check the Chroma window for errors."
    }
    Write-Host "[OK] Chroma heartbeat is ready." -ForegroundColor Green
}

Write-Step "Building and starting Spring Boot backend (:${backendPort})"
$mvn = Resolve-Maven
$jar = Join-Path $Root "backend\target\visionary-tutor-backend-0.0.1-SNAPSHOT.jar"
if (-not (Test-Path $jar)) {
    & $mvn -f (Join-Path $Root "backend\pom.xml") -DskipTests package
}
Start-Process powershell -ArgumentList @(
    "-NoExit",
    "-ExecutionPolicy", "Bypass",
    "-Command",
    "Set-Location '$Root'; java -jar '$jar' --server.port=$backendPort"
) | Out-Null
$health = Wait-BackendHealth
Write-Host "[OK] Backend is UP (demoMode=$($health.demoMode))." -ForegroundColor Green

Write-Step "Starting Vue frontend (:${frontendPort})"
$frontendDir = Join-Path $Root "frontend"
if (-not (Test-Path (Join-Path $frontendDir "node_modules"))) {
    Push-Location $frontendDir
    npm install
    Pop-Location
}
Start-Process powershell -ArgumentList @(
    "-NoExit",
    "-ExecutionPolicy", "Bypass",
    "-Command",
    "Set-Location '$frontendDir'; npm run dev -- --host 127.0.0.1 --port $frontendPort"
) | Out-Null
Write-Host "[OK] Frontend dev server launching at http://127.0.0.1:${frontendPort}" -ForegroundColor Green

Write-Step "RAG quick smoke (grounded=true)"
$ragQuery = "CNN padding stride"
$ragUri = "http://127.0.0.1:${backendPort}/api/admin/knowledge/rag-diagnostic?query=$([uri]::EscapeDataString($ragQuery))"
try {
    $rag = Invoke-RestMethod -Uri $ragUri -TimeoutSec 45
    $grounded = [bool]$rag.grounded
    $status = [string]$rag.status
    $citations = if ($rag.citations) { @($rag.citations).Count } else { 0 }
    if ($grounded) {
        Write-Host "[PASS] RAG grounded=true status=$status citations=$citations" -ForegroundColor Green
    } else {
        Write-Host "[WARN] RAG grounded=false status=$status citations=$citations" -ForegroundColor Yellow
        Write-Host "       Run ai_engine/document_processor.py if the knowledge base is empty." -ForegroundColor Yellow
    }
} catch {
    Write-Host "[WARN] RAG smoke failed: $($_.Exception.Message)" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "All services launched in separate terminals." -ForegroundColor Green
Write-Host "  Frontend : http://127.0.0.1:${frontendPort}"
Write-Host "  Backend  : http://127.0.0.1:${backendPort}/api/health"
Write-Host "  Chroma   : http://${chromaHost}:${chromaPort}/api/v2/heartbeat"
