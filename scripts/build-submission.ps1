param(
    [string]$OutputDirectory = "submission-dist"
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$out = [System.IO.Path]::GetFullPath((Join-Path $root $OutputDirectory))
if (-not $out.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase) -or $out -eq $root) {
    throw "Submission output must be a child of workspace: $out"
}

& (Join-Path $PSScriptRoot 'build-dataset-manifest.ps1')
$manifest = Get-Content -Raw -LiteralPath (Join-Path $root 'datasets/manifest.json') | ConvertFrom-Json

if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$sourceRoots = @('backend', 'frontend', 'ai_engine', 'scripts', 'tests', 'loadtest', 'datasets', '提交材料', '.github', '.mvn')
$excludedSegments = @('.venv', 'node_modules', 'target', 'dist', 'coverage', 'test-results', 'playwright-report',
    'rag_md_files', 'processed', 'embeddings', 'vector_store', '__pycache__', '.pytest_cache', '.playwright-cli', 'output')

foreach ($sourceRoot in $sourceRoots) {
    $sourcePath = Join-Path $root $sourceRoot
    if (-not (Test-Path -LiteralPath $sourcePath)) { continue }
    Get-ChildItem -LiteralPath $sourcePath -Recurse -File | Where-Object {
        $relative = $_.FullName.Substring($root.Length + 1)
        -not ($excludedSegments | Where-Object { ($relative -split '[\\/]') -contains $_ })
    } | ForEach-Object {
        $relative = $_.FullName.Substring($root.Length + 1)
        if ($relative -like 'ai_engine\knowledge_base\cleaned\*') { return }
        $destination = Join-Path $out $relative
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
        Copy-Item -LiteralPath $_.FullName -Destination $destination
    }
}

foreach ($document in $manifest.documents | Where-Object submission_eligible) {
    $source = Join-Path $root ($document.normalized_path -replace '/', '\')
    $destination = Join-Path $out ($document.normalized_path -replace '/', '\')
    $actualHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $document.sha256) { throw "Dataset hash mismatch: $($document.normalized_path)" }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination
}

$rootFiles = @('.editorconfig', '.env.example', '.gitattributes', '.gitignore', 'mvnw', 'mvnw.cmd',
    'README.md', 'THIRD_PARTY.md', 'docker-compose.prod.yml', 'docker-compose.rag-eval.yml')
foreach ($file in $rootFiles) {
    $source = Join-Path $root $file
    if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination (Join-Path $out $file) }
}

$inventory = Get-ChildItem -LiteralPath $out -Recurse -File | ForEach-Object {
    [ordered]@{
        path = $_.FullName.Substring($out.Length + 1) -replace '\\','/'
        bytes = $_.Length
        sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
$inventory | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $out 'SUBMISSION_SHA256.json') -Encoding UTF8

$zip = "$out.zip"
if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
# Windows bsdtar avoids intermittent OneDrive memory-mapping failures from Compress-Archive.
Push-Location $out
try {
    & tar.exe -a -c -f $zip *
    if ($LASTEXITCODE -ne 0) { throw "tar.exe failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
Write-Host "Submission directory: $out"
Write-Host "Submission archive: $zip"
Write-Host "Files: $(@($inventory).Count); size: $([math]::Round((Get-Item $zip).Length / 1MB, 1)) MB"
