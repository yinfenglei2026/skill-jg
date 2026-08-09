$ErrorActionPreference = 'Stop'

if (-not [string]::Equals($env:LIVE_HARBOR_COSIGN, 'true', [StringComparison]::OrdinalIgnoreCase)) {
    Write-Host 'Live Harbor/Cosign interoperability test skipped (set LIVE_HARBOR_COSIGN=true to enable).'
    exit 0
}

$required = @(
    'HARBOR_REGISTRY', 'HARBOR_USERNAME', 'HARBOR_PASSWORD', 'HARBOR_CA_CERT',
    'COSIGN_EXECUTABLE', 'COSIGN_PUBLIC_KEY', 'LIVE_ARTIFACT',
    'LIVE_SOURCE_REPOSITORY', 'LIVE_SOURCE_REVISION', 'SLSA_ALLOWED_BUILDER_IDS',
    'LIVE_WRONG_PASSWORD', 'LIVE_UNAUTHORIZED_ARTIFACT', 'LIVE_WRONG_PUBLIC_KEY',
    'LIVE_ARTIFACT_NO_ATTESTATION', 'LIVE_NO_ATTESTATION_PUBLIC_KEY'
)
foreach ($name in $required) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "LIVE_HARBOR_COSIGN requires $name"
    }
}

$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$wrapperJar = Join-Path $repositoryRoot '.mvn/wrapper/maven-wrapper.jar'
if (-not (Test-Path -LiteralPath $wrapperJar -PathType Leaf)) {
    throw "Maven wrapper jar is missing: $wrapperJar"
}

$originalLocation = Get-Location
try {
    Set-Location -LiteralPath $repositoryRoot
    & java "-Dmaven.multiModuleProjectDirectory=$repositoryRoot" -classpath $wrapperJar `
        org.apache.maven.wrapper.MavenWrapperMain -B -pl apps/control-plane `
        '-Dtest=LiveHarborCosignInteropTest' test
}
finally {
    Set-Location -LiteralPath $originalLocation
}
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$summaryPath = if ([string]::IsNullOrWhiteSpace($env:LIVE_EVIDENCE_OUT)) {
    Join-Path ([IO.Path]::GetTempPath()) 'skill-jg-live-harbor-cosign-summary.json'
} else { $env:LIVE_EVIDENCE_OUT }
$digest = [regex]::Match($env:LIVE_ARTIFACT, '@(?<digest>sha256:[0-9a-f]{64})$').Groups['digest'].Value
if ([string]::IsNullOrEmpty($digest)) {
    throw 'LIVE_ARTIFACT must be a digest reference'
}
$testCommit = (& cmd.exe /d /c ('git -C "{0}" rev-parse HEAD' -f $repositoryRoot)).Trim()
if ($LASTEXITCODE -ne 0 -or $testCommit -notmatch '^[0-9a-f]{40}$') {
    throw 'Could not resolve the tested Git commit'
}
$diffPath = [IO.Path]::GetTempFileName()
try {
    & cmd.exe /d /s /c ('git -C "{0}" diff --binary HEAD -- > "{1}"' -f $repositoryRoot, $diffPath)
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not materialize the tested Git diff'
    }
    $testDiffSha256 = (Get-FileHash -LiteralPath $diffPath -Algorithm SHA256).Hash.ToLowerInvariant()
}
finally {
    if (Test-Path -LiteralPath $diffPath -PathType Leaf) {
        [IO.File]::Delete($diffPath)
    }
}
$summary = [ordered]@{
    liveHarborCosign = $true
    status = 'PASS'
    artifactDigest = $digest
    testCommit = $testCommit
    testDiffSha256 = $testDiffSha256
    matrix = @(
        [ordered]@{ name = 'positive'; status = 'PASS' }
        [ordered]@{ name = 'wrongPassword'; status = 'EXPECTED_FAILURE'; failureCategory = 'REGISTRY_AUTH_FAILED' }
        [ordered]@{ name = 'unauthorizedProject'; status = 'EXPECTED_FAILURE'; failureCategory = 'REGISTRY_AUTH_FAILED' }
        [ordered]@{ name = 'wrongPublicKey'; status = 'EXPECTED_FAILURE'; failureCategory = 'SIGNATURE_INVALID' }
        [ordered]@{ name = 'missingAttestation'; status = 'EXPECTED_FAILURE'; failureCategory = 'PROVENANCE_INVALID' }
        [ordered]@{ name = 'builderMismatch'; status = 'EXPECTED_FAILURE'; failureCategory = 'PROVENANCE_POLICY_MISMATCH' }
        [ordered]@{ name = 'repositoryMismatch'; status = 'EXPECTED_FAILURE'; failureCategory = 'PROVENANCE_POLICY_MISMATCH' }
        [ordered]@{ name = 'revisionMismatch'; status = 'EXPECTED_FAILURE'; failureCategory = 'PROVENANCE_POLICY_MISMATCH' }
        [ordered]@{ name = 'harborUnavailable'; status = 'EXPECTED_FAILURE'; failureCategory = 'REGISTRY_UNAVAILABLE' }
    )
}
$summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $summaryPath -Encoding utf8
Write-Host "Live Harbor/Cosign interoperability passed. Redacted summary: $summaryPath"
