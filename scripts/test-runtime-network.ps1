$ErrorActionPreference = 'Stop'

$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$rendered = (kubectl kustomize (Join-Path $repositoryRoot 'infra/k8s/base')) -join "`n"
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$requiredPatterns = @(
    'runtimeClassName:\s+gvisor',
    'name:\s+default-deny-all',
    'readOnlyRootFilesystem:\s+true',
    'name:\s+runtime-dns-egress',
    'kubernetes.io/metadata.name:\s+kube-system',
    'k8s-app:\s+kube-dns',
    'port:\s+53\s+protocol:\s+UDP',
    'port:\s+53\s+protocol:\s+TCP'
)

foreach ($required in $requiredPatterns) {
    if ($rendered -notmatch $required) {
        throw "Rendered Kubernetes resources are missing required pattern: $required"
    }
}

Write-Host 'Runtime network policy verification passed.'
