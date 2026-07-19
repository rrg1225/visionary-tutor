# Start Chroma HTTP server for VisionaryTutor (Java backend + document_processor.py)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$hostAddr = "127.0.0.1"
$port = 8000
$dataDir = if ($env:CHROMA_DATA_DIR) {
    $env:CHROMA_DATA_DIR
} else {
    Join-Path $Root "visionary_chroma_data"
}
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null

function Test-ChromaHeartbeat {
    try {
        $r = Invoke-WebRequest -Uri "http://${hostAddr}:${port}/api/v2/heartbeat" -UseBasicParsing -TimeoutSec 3
        return ($r.StatusCode -eq 200)
    } catch {
        return $false
    }
}

if (Test-ChromaHeartbeat) {
    Write-Host "Chroma is already running at http://${hostAddr}:${port} (heartbeat OK)."
    Write-Host "You can run document_processor.py in another terminal. No need to start again."
    exit 0
}

$listener = netstat -ano | Select-String "LISTENING" | Select-String ":${port}\s"
if ($listener) {
    $blockerPid = ($listener -split '\s+')[-1]
    Write-Host "Port ${port} is in use by PID $blockerPid but it is NOT a healthy Chroma server."
    Write-Host "Stop that process first, for example:"
    Write-Host "  taskkill /PID $blockerPid /F"
    Write-Host "Then run this script again."
    exit 1
}

Write-Host "Starting Chroma at http://${hostAddr}:${port} (data: $dataDir)"
Write-Host "Keep this window open. Press Ctrl+C to stop."
& "$Root\.venv\Scripts\chroma.exe" run --path $dataDir --host $hostAddr --port $port
