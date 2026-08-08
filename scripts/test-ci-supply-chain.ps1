$ErrorActionPreference = 'Stop'

$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$gitleaksConfigPath = Join-Path $repositoryRoot '.gitleaks.toml'
$pomPath = Join-Path $repositoryRoot 'apps\control-plane\pom.xml'
$portalPackagePath = Join-Path $repositoryRoot 'apps\portal\package.json'
$rootVerifierPath = Join-Path $repositoryRoot 'scripts\verify.ps1'
$workflowPath = Join-Path $repositoryRoot '.github\workflows\ci.yml'
if (-not (Test-Path -LiteralPath $pomPath)) {
    throw 'Control-plane Maven project is missing: apps/control-plane/pom.xml'
}
if (-not (Test-Path -LiteralPath $workflowPath)) {
    throw 'CI workflow is missing: .github/workflows/ci.yml'
}
if (-not (Test-Path -LiteralPath $portalPackagePath)) {
    throw 'Portal package manifest is missing: apps/portal/package.json'
}
if (-not (Test-Path -LiteralPath $rootVerifierPath)) {
    throw 'Root verifier is missing: scripts/verify.ps1'
}
if (-not (Test-Path -LiteralPath $gitleaksConfigPath)) {
    throw 'Gitleaks configuration is missing: .gitleaks.toml'
}

$pom = Get-Content -LiteralPath $pomPath -Raw
$requiredMavenSbomConfig = @(
    @{ Name = 'CycloneDX Maven plugin'; Pattern = '<artifactId>cyclonedx-maven-plugin</artifactId>' }
    @{ Name = 'CycloneDX Maven plugin version'; Pattern = '<version>2\.9\.2</version>' }
    @{ Name = 'aggregate BOM goal'; Pattern = '<goal>makeAggregateBom</goal>' }
    @{ Name = 'verify lifecycle binding'; Pattern = '<phase>verify</phase>' }
    @{ Name = 'CycloneDX JSON format'; Pattern = '<outputFormat>json</outputFormat>' }
    @{ Name = 'CycloneDX 1.6 schema'; Pattern = '<schemaVersion>1\.6</schemaVersion>' }
    @{ Name = 'disabled random BOM serial number'; Pattern = '<includeBomSerialNumber>false</includeBomSerialNumber>' }
    @{ Name = 'fixed BOM output name'; Pattern = '<outputName>control-plane-bom</outputName>' }
    @{ Name = 'fixed BOM output directory'; Pattern = '<outputDirectory>\$\{project\.build\.directory\}/sbom</outputDirectory>' }
)
$missingMavenSbomConfig = @(
    foreach ($requirement in $requiredMavenSbomConfig) {
        if ($pom -notmatch $requirement.Pattern) {
            $requirement.Name
        }
    }
)
if ($missingMavenSbomConfig.Count -gt 0) {
    throw "Control-plane Maven project lacks required SBOM configuration: $($missingMavenSbomConfig -join '; ')."
}

$portalPackage = Get-Content -LiteralPath $portalPackagePath -Raw | ConvertFrom-Json
if ($portalPackage.devDependencies.'@cyclonedx/cyclonedx-npm' -ne '4.2.1') {
    throw 'Portal must lock @cyclonedx/cyclonedx-npm at version 4.2.1.'
}

$gitleaksConfig = Get-Content -LiteralPath $gitleaksConfigPath -Raw
if ($gitleaksConfig -notmatch '(?m)^\s*useDefault\s*=\s*true\s*$') {
    throw 'Gitleaks configuration must extend the built-in default rules.'
}
if ($gitleaksConfig -match '(?im)^\s*paths\s*=') {
    throw 'Gitleaks configuration must not allowlist paths.'
}
if ($gitleaksConfig -notmatch '(?m)^\s*regexTarget\s*=\s*"match"\s*$' -or
    $gitleaksConfig -notmatch 'replace-with-local-test-user-password' -or
    $gitleaksConfig -notmatch [regex]::Escape('verify-(?:admin|governance|keycloak)')) {
    throw 'Gitleaks configuration must allowlist only the exact documented synthetic values.'
}

$rootVerifier = Get-Content -LiteralPath $rootVerifierPath -Raw
foreach ($contractScript in @('test-build-metadata.ps1', 'test-ci-supply-chain.ps1', 'test-cosign-install.ps1')) {
    if ($rootVerifier -notmatch [regex]::Escape($contractScript)) {
        throw "Root verifier must run $contractScript."
    }
}
if ($rootVerifier -notmatch '(?s)test-build-metadata\.ps1' -or $rootVerifier -notmatch 'if \(-not \$\?\)') {
    throw 'Root verifier must use PowerShell success state after running supply-chain contract scripts.'
}

$workflow = Get-Content -LiteralPath $workflowPath -Raw
$requiredEvidence = @(
    @{ Name = 'full Git history (fetch-depth: 0)'; Pattern = '(?m)^\s*fetch-depth:\s*0\s*$' }
    @{ Name = 'pinned Gitleaks version'; Pattern = '(?m)^\s*GITLEAKS_VERSION\s*=\s*8\.29\.1\s*$' }
    @{ Name = 'pinned Gitleaks checksum'; Pattern = '(?m)^\s*GITLEAKS_SHA256\s*=' }
    @{ Name = 'Gitleaks checksum validation'; Pattern = '(?i)sha256sum\s+--check' }
    @{ Name = 'Gitleaks version validation'; Pattern = '(?i)(?:gitleaks|GITLEAKS_BIN).*\bversion\b' }
    @{ Name = 'redacted Gitleaks output'; Pattern = '(?i)--redact\b' }
    @{ Name = 'Gitleaks SARIF report'; Pattern = '(?i)--report-format\s+sarif\b' }
    @{ Name = 'Gitleaks JSON report'; Pattern = '(?i)--report-format\s+json\b' }
    @{ Name = 'CycloneDX Maven output validation'; Pattern = 'control-plane-bom\.json' }
    @{ Name = 'CycloneDX npm command'; Pattern = 'node\s+node_modules/@cyclonedx/cyclonedx-npm/bin/cyclonedx-npm-cli\.js' }
    @{ Name = 'build metadata writer'; Pattern = 'write-build-metadata\.ps1' }
    @{ Name = 'evidence artifact upload action'; Pattern = 'actions/upload-artifact@v4' }
    @{ Name = 'commit-scoped evidence artifact name'; Pattern = [regex]::Escape('ci-evidence-${{ github.sha }}') }
    @{ Name = 'strict evidence artifact upload'; Pattern = '(?m)^\s*if-no-files-found:\s*error\s*$' }
    @{ Name = 'pinned Cosign installer'; Pattern = 'install-cosign\.ps1' }
    @{ Name = 'Cosign version validation'; Pattern = '(?i)cosign[^\r\n]*version' }
    @{ Name = 'Cosign executable export'; Pattern = 'COSIGN_EXECUTABLE' }
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

if ($workflow -match '(?i)cosign[^\r\n]{0,120}\blatest\b') {
    throw 'CI workflow must not use a floating Cosign version.'
}
foreach ($forbidden in @('--allow-http-registry', '--allow-insecure-registry',
        '--insecure-ignore-tlog', '--registry-password', '--registry-token')) {
    if ($workflow -match [regex]::Escape($forbidden)) {
        throw "CI workflow contains forbidden Cosign token: $forbidden"
    }
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
