[CmdletBinding()]
param(
    [string]$BaseUrl = 'https://zhiyexueye.top',
    [string]$Token = '',
    [string]$FixedPaperCode = 'cnn-convolution-v1',
    [string]$KnowledgeSlug = 'linear-algebra-for-ai'
)

$ErrorActionPreference = 'Stop'
$base = $BaseUrl.TrimEnd('/')
$headers = @{}
if ($Token) { $headers.Authorization = "Bearer $Token" }

function Check([string]$Name, [scriptblock]$Action) {
    try {
        $result = & $Action
        Write-Host "[PASS] $Name" -ForegroundColor Green
        return $result
    } catch {
        Write-Host "[FAIL] $Name - $($_.Exception.Message)" -ForegroundColor Red
        $script:failed = $true
        return $null
    }
}

$script:failed = $false

$frontend = Check 'frontend document' {
    Invoke-WebRequest "$base/?deployment_check=$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())" `
        -Headers @{ 'Cache-Control' = 'no-cache' } `
        -UseBasicParsing
}

if ($frontend) {
    $assetPaths = [regex]::Matches(
        $frontend.Content,
        '(?i)(?:src|href)\s*=\s*["''](?<path>/[^"''?#]+)(?:[?#][^"'']*)?["'']'
    ) | ForEach-Object { $_.Groups['path'].Value } | Sort-Object -Unique

    if (-not $assetPaths) {
        Write-Host '[FAIL] frontend document does not reference any static assets' -ForegroundColor Red
        $script:failed = $true
    }

    foreach ($assetPath in $assetPaths) {
        Check "frontend asset $assetPath" {
            $asset = Invoke-WebRequest "$base$assetPath" -Method Head -UseBasicParsing
            if ($asset.StatusCode -ne 200) {
                throw "HTTP $($asset.StatusCode)"
            }
            $asset
        } | Out-Null
    }
}

$meta = Check 'build metadata' { Invoke-RestMethod "$base/api/meta/build" -Headers $headers }
Check 'public health' { Invoke-RestMethod "$base/api/health" -Headers $headers } | Out-Null

if ($meta) {
    $gitExe = Get-Command git -ErrorAction SilentlyContinue
    $localSha = if ($gitExe) { (& git rev-parse HEAD).Trim() } else { '' }
    Write-Host "Deployed git SHA: $($meta.gitSha); build time: $($meta.buildTime)"
    if ($localSha -and $meta.gitSha -and $meta.gitSha -ne 'unknown' -and -not $localSha.StartsWith($meta.gitSha)) {
        Write-Host "[FAIL] deployed build does not match local HEAD $localSha" -ForegroundColor Red
        $script:failed = $true
    }
}

if ($Token) {
    Check 'registered user can read fixed paper' { Invoke-RestMethod "$base/api/fixed-exams/$FixedPaperCode" -Headers $headers } | Out-Null
    Check 'registered user can read system knowledge' { Invoke-RestMethod "$base/api/knowledge-content/$KnowledgeSlug" -Headers $headers } | Out-Null
} else {
    Write-Host '[SKIP] protected smoke checks (pass -Token with a registered-user JWT)' -ForegroundColor Yellow
}

if ($script:failed) { exit 1 }
exit 0
