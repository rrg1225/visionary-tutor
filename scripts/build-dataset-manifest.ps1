param(
    [string]$OutputPath = "datasets/manifest.json"
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$metadataPath = Join-Path $root 'ai_engine/knowledge_base/metadata/catalog.md'
$cleanedRoot = Join-Path $root 'ai_engine/knowledge_base'
$resolvedOutput = [System.IO.Path]::GetFullPath((Join-Path $root $OutputPath))
if (-not $resolvedOutput.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Output must stay inside workspace: $resolvedOutput"
}

$licenseRules = @(
    @{ Prefix = 'd2l-en-master/'; License = 'CC-BY-SA-4.0'; Redistribution = 'allowed'; Note = 'Retain attribution and share-alike notice.' },
    @{ Prefix = 'pytorch/'; License = 'BSD-3-Clause'; Redistribution = 'allowed'; Note = 'Retain upstream copyright and license notice.' },
    @{ Prefix = 'hello-algo/'; License = 'CC-BY-NC-SA-4.0'; Redistribution = 'allowed_noncommercial'; Note = 'Competition and non-commercial teaching only.' },
    @{ Prefix = 'cs229/'; License = 'REVIEW_REQUIRED'; Redistribution = 'excluded'; Note = 'Course-material redistribution permission not established in repository.' },
    @{ Prefix = 'CS231n-Note-Translation_CN-master/'; License = 'REVIEW_REQUIRED'; Redistribution = 'excluded'; Note = 'Translation and upstream permissions must both be verified.' }
)

function Resolve-License([string]$sourcePath) {
    foreach ($rule in $licenseRules) {
        if ($sourcePath.StartsWith($rule.Prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            return $rule
        }
    }
    return @{ Prefix = ''; License = 'PROPRIETARY_OR_UNKNOWN'; Redistribution = 'excluded'; Note = 'No redistribution evidence; excluded by default.' }
}

$catalogPattern = '^- \[(?<title>.*)\]\(\.\./cleaned/(?<file>[^)]+)\) `(?<type>[^`]+)` from `(?<source>[^`]+)`$'
$sources = Get-Content -LiteralPath $metadataPath -Encoding UTF8 | ForEach-Object {
    if ($_ -match $catalogPattern) {
        [pscustomobject]@{
            doc_id = [System.IO.Path]::GetFileNameWithoutExtension($Matches.file)
            title = $Matches.title
            source_path = $Matches.source
            source_type = $Matches.type
            cleaned_path = "cleaned/$($Matches.file)"
        }
    }
}
$documents = foreach ($source in $sources) {
    $file = Join-Path $cleanedRoot $source.cleaned_path
    if (-not (Test-Path -LiteralPath $file)) { continue }
    $license = Resolve-License ([string]$source.source_path)
    $headings = (Select-String -LiteralPath $file -Pattern '^#{1,6}\s+' -Encoding UTF8).Count
    [ordered]@{
        id = $source.doc_id
        title = $source.title
        source_path = $source.source_path
        normalized_path = "ai_engine/knowledge_base/$($source.cleaned_path -replace '\\','/')"
        source_type = $source.source_type
        license = $license.License
        redistribution = $license.Redistribution
        license_note = $license.Note
        sha256 = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
        bytes = (Get-Item -LiteralPath $file).Length
        chapter_count = $headings
        processing = @('source inventory', 'text normalization', 'content-hash deduplication', 'heading-aware semantic chunking')
        submission_eligible = $license.Redistribution -like 'allowed*'
    }
}

$manifest = [ordered]@{
    schema_version = '1.0'
    generated_at = (Get-Date).ToUniversalTime().ToString('o')
    policy = 'deny-by-default redistribution; derived chunks and vector stores are rebuilt, not shipped'
    document_count = @($documents).Count
    eligible_document_count = @($documents | Where-Object submission_eligible).Count
    excluded_document_count = @($documents | Where-Object { -not $_.submission_eligible }).Count
    documents = @($documents)
}

$directory = Split-Path -Parent $resolvedOutput
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resolvedOutput -Encoding UTF8
Write-Host "Dataset manifest: $resolvedOutput"
Write-Host "Eligible: $($manifest.eligible_document_count); excluded: $($manifest.excluded_document_count)"
