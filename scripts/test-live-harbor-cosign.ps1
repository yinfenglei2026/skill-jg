$ErrorActionPreference = 'Stop'

if ($env:LIVE_HARBOR_COSIGN -notmatch '^(?i:true|1|yes)$') {
    Write-Host 'Live Harbor/Cosign interoperability test skipped (set LIVE_HARBOR_COSIGN=true to enable).'
    exit 0
}

$required = @(
    'HARBOR_REGISTRY', 'HARBOR_USERNAME', 'HARBOR_PASSWORD', 'HARBOR_CA_CERT',
    'COSIGN_EXECUTABLE', 'COSIGN_PUBLIC_KEY', 'LIVE_ARTIFACT',
    'LIVE_SOURCE_REPOSITORY', 'LIVE_SOURCE_REVISION', 'SLSA_ALLOWED_BUILDER_IDS'
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

& java "-Dmaven.multiModuleProjectDirectory=$repositoryRoot" -classpath $wrapperJar `
    org.apache.maven.wrapper.MavenWrapperMain -B -pl apps/control-plane `
    '-Dtest=LiveHarborCosignInteropTest' test
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$summaryPath = if ([string]::IsNullOrWhiteSpace($env:LIVE_EVIDENCE_OUT)) {
    Join-Path ([IO.Path]::GetTempPath()) 'skill-jg-live-harbor-cosign-summary.json'
} else { $env:LIVE_EVIDENCE_OUT }
$summary = [ordered]@{
    liveHarborCosign = $true
    status = 'PASS'
    artifactDigest = $env:LIVE_ARTIFACT
    sourceRepository = $env:LIVE_SOURCE_REPOSITORY
    sourceRevision = $env:LIVE_SOURCE_REVISION
    builderId = $env:SLSA_ALLOWED_BUILDER_IDS
    testCommit = (& git -C $repositoryRoot rev-parse HEAD).Trim()
}
$summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $summaryPath -Encoding utf8
Write-Host "Live Harbor/Cosign interoperability passed. Redacted summary: $summaryPath"
