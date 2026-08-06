$ErrorActionPreference = 'Stop'

$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$workflowPath = Join-Path $repositoryRoot '.github\workflows\ci.yml'
if (-not (Test-Path -LiteralPath $workflowPath)) {
    throw 'CI workflow is missing: .github/workflows/ci.yml'
}

$workflow = Get-Content -LiteralPath $workflowPath -Raw
$requiredEvidence = @(
    @{ Name = 'full Git history (fetch-depth: 0)'; Pattern = '(?m)^\s*fetch-depth:\s*0\s*$' }
    @{ Name = 'pinned Gitleaks version'; Pattern = '(?m)^\s*GITLEAKS_VERSION\s*=' }
    @{ Name = 'pinned Gitleaks checksum'; Pattern = '(?m)^\s*GITLEAKS_SHA256\s*=' }
    @{ Name = 'Gitleaks checksum validation'; Pattern = '(?i)sha256sum\s+--check' }
    @{ Name = 'Gitleaks version validation'; Pattern = '(?i)\bgitleaks\s+version\b' }
    @{ Name = 'redacted Gitleaks output'; Pattern = '(?i)--redact\b' }
    @{ Name = 'Gitleaks SARIF report'; Pattern = '(?i)--report-format\s+sarif\b' }
    @{ Name = 'Gitleaks JSON report'; Pattern = '(?i)--report-format\s+json\b' }
    @{ Name = 'CycloneDX Maven command'; Pattern = '(?i)cyclonedx-maven-plugin' }
    @{ Name = 'CycloneDX npm command'; Pattern = '(?i)(?:npx\s+@cyclonedx/cyclonedx-npm|npm\s+(?:exec|run).*cyclonedx)' }
    @{ Name = 'build metadata writer'; Pattern = 'write-build-metadata\.ps1' }
    @{ Name = 'evidence artifact upload action'; Pattern = 'actions/upload-artifact@v4' }
    @{ Name = 'commit-scoped evidence artifact name'; Pattern = [regex]::Escape('ci-evidence-${{ github.sha }}') }
    @{ Name = 'strict evidence artifact upload'; Pattern = '(?m)^\s*if-no-files-found:\s*error\s*$' }
)

$missingEvidence = @(
    foreach ($requirement in $requiredEvidence) {
        if ($workflow -notmatch $requirement.Pattern) {
            $requirement.Name
        }
    }
)
if ($missingEvidence.Count -gt 0) {
    throw "CI workflow lacks required supply-chain evidence steps: $($missingEvidence -join '; ')."
}

if ($workflow -match '(?i)\bGITLEAKS_LICENSE\b') {
    throw 'CI workflow must not use GITLEAKS_LICENSE.'
}
if ($workflow -match '@main\b') {
    throw 'CI workflow must not reference mutable @main actions or tools.'
}

$broadIgnoredPathPatterns = @(
    '(?im)^\s*paths-ignore\s*:\s*$',
    '(?i)--(?:exclude|ignore)-path(?:=|\s+)(?:["'']?)(?:\.\*|\*{1,2}/?\*?)',
    '(?i)(?:allowlist|whitelist)[\s\S]{0,500}(?:path|paths)\s*:\s*[\s\S]{0,500}(?:\.\*|\*\*/\*|\*\*)'
)
foreach ($pattern in $broadIgnoredPathPatterns) {
    if ($workflow -match $pattern) {
        throw 'CI workflow must not suppress broad paths from supply-chain evidence checks.'
    }
}

Write-Host 'CI supply-chain evidence contract passed.'
