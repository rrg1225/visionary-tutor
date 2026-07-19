[CmdletBinding()]
param(
    [switch]$SkipE2E,
    [switch]$SkipAiTests,
    [switch]$IncludeIntegration
)

$ErrorActionPreference = 'Stop'
$utf8Encoding = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding = $utf8Encoding
[Console]::OutputEncoding = $utf8Encoding
$OutputEncoding = $utf8Encoding
$scriptPath = $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent (Split-Path -Parent $scriptPath)
# Keep this script ASCII-only so Windows PowerShell 5 can parse it without a BOM.
$evidenceDirectoryName = '03_' + [char]0x6D4B + [char]0x8BD5 + [char]0x8BC1 + [char]0x636E
$ReportDirectory = Join-Path (Join-Path $RepoRoot $evidenceDirectoryName) 'reports'
$JsonReport = Join-Path $ReportDirectory 'competition_verification_latest.json'
$MarkdownReport = Join-Path $ReportDirectory 'competition_verification_latest.md'
$script:checks = New-Object System.Collections.Generic.List[object]

function Invoke-ExternalCheck {
    param(
        [string]$Name,
        [string]$WorkingDirectory,
        [string]$Command,
        [string[]]$Arguments,
        [hashtable]$Environment = @{}
    )

    Write-Host "`n=== $Name ===" -ForegroundColor Cyan
    $startedAt = Get-Date
    $exitCode = 1
    $detail = ''
    $previousEnvironment = @{}
    Push-Location $WorkingDirectory
    try {
        foreach ($key in $Environment.Keys) {
            $previousEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
            [Environment]::SetEnvironmentVariable($key, [string]$Environment[$key], 'Process')
        }
        # Native tools legitimately write warnings to stderr. PowerShell 5 turns
        # redirected stderr into ErrorRecord objects, so success must be decided
        # by the native process exit code rather than ErrorActionPreference.
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        & $Command @Arguments 2>&1 | ForEach-Object { Write-Host $_ }
        $exitCode = $LASTEXITCODE
        $ErrorActionPreference = $previousErrorActionPreference
        if ($null -eq $exitCode) {
            $exitCode = 0
        }
        $detail = if ($exitCode -eq 0) { 'completed' } else { "exitCode=$exitCode" }
    } catch {
        $ErrorActionPreference = 'Stop'
        $detail = $_.Exception.Message
        $exitCode = 1
        Write-Host $detail -ForegroundColor Red
    } finally {
        foreach ($key in $Environment.Keys) {
            [Environment]::SetEnvironmentVariable($key, $previousEnvironment[$key], 'Process')
        }
        Pop-Location
    }

    $duration = [Math]::Round(((Get-Date) - $startedAt).TotalSeconds, 2)
    $passed = $exitCode -eq 0
    $script:checks.Add([PSCustomObject]@{
        name = $Name
        passed = $passed
        durationSeconds = $duration
        detail = $detail
    })
    $color = if ($passed) { 'Green' } else { 'Red' }
    Write-Host "[$(if ($passed) { 'PASS' } else { 'FAIL' })] $Name (${duration}s)" -ForegroundColor $color
}

function Get-DockerIntegrationEnvironment {
    $environment = @{}
    $dockerHost = $env:DOCKER_HOST
    if ([string]::IsNullOrWhiteSpace($dockerHost)) {
        $dockerHost = (& docker context inspect --format '{{.Endpoints.docker.Host}}' 2>$null | Select-Object -First 1)
        if ($LASTEXITCODE -ne 0) {
            $dockerHost = $null
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($dockerHost)) {
        $dockerHost = ([string]$dockerHost).Trim()
        $environment['DOCKER_HOST'] = $dockerHost
        if ($dockerHost.StartsWith('npipe:', [StringComparison]::OrdinalIgnoreCase)) {
            $environment['TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE'] = '/var/run/docker.sock'
        }
    }
    return $environment
}

$maven = Join-Path $RepoRoot 'mvnw.cmd'
if (-not (Test-Path -LiteralPath $maven)) {
    throw 'Maven Wrapper is missing. Restore mvnw.cmd and .mvn/wrapper/maven-wrapper.properties.'
}

Invoke-ExternalCheck -Name 'Encoding and mojibake guard' -WorkingDirectory $RepoRoot `
    -Command 'powershell.exe' `
    -Arguments @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $RepoRoot 'scripts\check-encoding.ps1'))

Invoke-ExternalCheck -Name 'Backend tests and quality gates' -WorkingDirectory $RepoRoot `
    -Command $maven `
    -Arguments @('-f', (Join-Path $RepoRoot 'backend\pom.xml'), 'verify')

if ($IncludeIntegration) {
    $dockerEnvironment = Get-DockerIntegrationEnvironment
    Invoke-ExternalCheck -Name 'Real MySQL Redis Chroma learning loop' -WorkingDirectory $RepoRoot `
        -Command $maven `
        -Arguments @('-f', (Join-Path $RepoRoot 'backend\pom.xml'), '-Pintegration', 'verify') `
        -Environment $dockerEnvironment
}

Invoke-ExternalCheck -Name 'Frontend lint, coverage and production build' -WorkingDirectory (Join-Path $RepoRoot 'frontend') `
    -Command 'npm.cmd' `
    -Arguments @('run', 'quality')

if (-not $SkipE2E) {
    Invoke-ExternalCheck -Name 'Competition browser evidence' -WorkingDirectory (Join-Path $RepoRoot 'frontend') `
        -Command 'npm.cmd' `
        -Arguments @('run', 'test:e2e')
}

if (-not $SkipAiTests) {
    Invoke-ExternalCheck -Name 'AI engine evaluation tests' -WorkingDirectory $RepoRoot `
        -Command 'python' `
        -Arguments @('-m', 'pytest', 'ai_engine\tests', '-q')
}

$failed = @($checks | Where-Object { -not $_.passed })
$commit = (& git -C $RepoRoot rev-parse HEAD 2>$null)
$dirty = [bool]((& git -C $RepoRoot status --porcelain 2>$null) | Select-Object -First 1)
$report = [PSCustomObject]@{
    schemaVersion = 'competition-verification-v1'
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    gitCommit = [string]$commit
    dirtyWorktree = $dirty
    passed = $failed.Count -eq 0
    passCount = @($checks | Where-Object { $_.passed }).Count
    failCount = $failed.Count
    checks = $checks
}

New-Item -ItemType Directory -Force -Path $ReportDirectory | Out-Null
$report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $JsonReport -Encoding UTF8

$markdown = New-Object System.Collections.Generic.List[string]
$markdown.Add('# Competition Verification')
$markdown.Add('')
$markdown.Add("- Generated: $($report.generatedAt)")
$markdown.Add("- Commit: $($report.gitCommit)")
$markdown.Add("- Dirty worktree: $($report.dirtyWorktree)")
$markdown.Add("- Result: $(if ($report.passed) { 'PASS' } else { 'FAIL' })")
$markdown.Add('')
$markdown.Add('| Check | Result | Duration | Detail |')
$markdown.Add('|---|---:|---:|---|')
foreach ($check in $checks) {
    $result = if ($check.passed) { 'PASS' } else { 'FAIL' }
    $safeDetail = ([string]$check.detail).Replace('|', '\|').Replace("`r", ' ').Replace("`n", ' ')
    $markdown.Add("| $($check.name) | $result | $($check.durationSeconds)s | $safeDetail |")
}
$markdown | Set-Content -LiteralPath $MarkdownReport -Encoding UTF8

Write-Host "`nVerification report: $MarkdownReport" -ForegroundColor Cyan
if ($failed.Count -gt 0) {
    exit 1
}
exit 0
