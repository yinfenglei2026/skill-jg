$ErrorActionPreference = 'Stop'

$maven = 'C:\Users\leiyinfeng\.m2\wrapper\dists\apache-maven-3.9.9\977a63e90f436cd6ade95b4c0e10c20c\bin\mvn.cmd'
if (-not (Test-Path -LiteralPath $maven)) {
    throw "Maven 3.9.9 was not found at $maven. Install Maven or update this script for the local Maven path."
}

& $maven -pl apps/control-plane test
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Push-Location apps/portal
try {
    node --test test/release-view-model.test.mjs
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}

$rendered = (kubectl kustomize infra/k8s/base) -join "`n"
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
foreach ($required in @('runtimeClassName:\s+gvisor', 'name:\s+default-deny-all', 'readOnlyRootFilesystem:\s+true')) {
    if ($rendered -notmatch $required) {
        throw "Rendered Kubernetes resources are missing required pattern: $required"
    }
}

Write-Host 'Verification completed successfully.'
