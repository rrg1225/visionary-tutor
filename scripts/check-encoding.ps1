[CmdletBinding()]
param(
    [string]$Root
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Root)) {
    $scriptPath = $MyInvocation.MyCommand.Path
    if ([string]::IsNullOrWhiteSpace($scriptPath)) {
        throw 'Cannot resolve repository root because the current script path is unavailable.'
    }
    $Root = Split-Path -Parent (Split-Path -Parent $scriptPath)
}
$Root = (Resolve-Path -LiteralPath $Root).Path

$utf8Strict = New-Object System.Text.UTF8Encoding($false, $true)
$textExtensions = @(
    '.java', '.js', '.ts', '.vue', '.py', '.yml', '.yaml', '.json', '.jsonl',
    '.md', '.xml', '.properties', '.sql', '.ps1', '.vbs', '.css', '.html'
)
$excludedSegments = @(
    '\.git\', '\node_modules\', '\target\', '\dist\', '\.venv\',
    '\test-results\', '\playwright-report\', '\knowledge_base\processed\',
    '\knowledge_base\vector_store\'
)
# Keep this script ASCII-only so Windows PowerShell 5 can parse it without a BOM.
$mojibakePattern = '\u9365|\u6D93|\u9225|\u6D5C\u0443|\u74A7\u52EC|\u9422\u3126\u57DB|\u701B\uFE3F\u7BC4|\u7F01|\uFFFD'
$issues = New-Object System.Collections.Generic.List[string]

function Get-RepositoryRelativePath([string]$FilePath) {
    $prefix = $Root.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
    if ($FilePath.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $FilePath.Substring($prefix.Length).TrimStart(
            [System.IO.Path]::DirectorySeparatorChar,
            [System.IO.Path]::AltDirectorySeparatorChar
        )
    }
    return $FilePath
}

Get-ChildItem -LiteralPath $Root -Recurse -File | ForEach-Object {
    $file = $_
    if ($textExtensions -notcontains $file.Extension.ToLowerInvariant()) {
        return
    }
    foreach ($segment in $excludedSegments) {
        if ($file.FullName.ToLowerInvariant().Contains($segment)) {
            return
        }
    }

    try {
        $bytes = [System.IO.File]::ReadAllBytes($file.FullName)
        $text = $utf8Strict.GetString($bytes)
    } catch {
        $relative = Get-RepositoryRelativePath $file.FullName
        $issues.Add("$relative : invalid UTF-8 ($($_.Exception.Message))")
        return
    }

    $mojibakeMatch = [regex]::Match($text, $mojibakePattern)
    if ($mojibakeMatch.Success) {
        $relative = Get-RepositoryRelativePath $file.FullName
        $lineNumber = 1 + ([regex]::Matches($text.Substring(0, $mojibakeMatch.Index), "`n")).Count
        $issues.Add("$relative`:$lineNumber : suspicious mojibake '$($mojibakeMatch.Value)'")
    }
}

if ($issues.Count -gt 0) {
    Write-Host 'Encoding check failed:' -ForegroundColor Red
    $issues | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    exit 1
}

Write-Host 'Encoding check passed: all tracked source-style text is valid UTF-8 with no known mojibake markers.' -ForegroundColor Green
