$ErrorActionPreference = 'Stop'

$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$maven = Join-Path $repositoryRoot 'mvnw.cmd'
if (-not (Test-Path -LiteralPath $maven)) {
    throw "Maven Wrapper was not found at $maven."
}

& $maven -B -pl apps/control-plane verify
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Push-Location (Join-Path $repositoryRoot 'apps/portal')
try {
    node --test test/release-view-model.test.mjs
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}

$rendered = (kubectl kustomize (Join-Path $repositoryRoot 'infra/k8s/base')) -join "`n"
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
foreach ($required in @('runtimeClassName:\s+gvisor', 'name:\s+default-deny-all', 'readOnlyRootFilesystem:\s+true')) {
    if ($rendered -notmatch $required) {
        throw "Rendered Kubernetes resources are missing required pattern: $required"
    }
}

Write-Host 'Verification completed successfully.'
