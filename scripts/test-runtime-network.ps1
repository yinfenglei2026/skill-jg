$ErrorActionPreference = 'Stop'

$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$rendered = (kubectl kustomize (Join-Path $repositoryRoot 'infra/k8s/base')) -join "`n"
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$requiredGlobalPatterns = @(
    'runtimeClassName:\s+gvisor',
    'name:\s+default-deny-all',
    'readOnlyRootFilesystem:\s+true'
)
foreach ($required in $requiredGlobalPatterns) {
    if ($rendered -notmatch $required) {
        throw "Rendered Kubernetes resources are missing required pattern: $required"
    }
}

$dnsPolicies = @([regex]::Split($rendered, '(?m)^---\s*$') | Where-Object {
    $_ -match '(?m)^kind:\s+NetworkPolicy\s*$' -and
    $_ -match '(?m)^\s*name:\s+runtime-dns-egress\s*$'
})
if ($dnsPolicies.Count -ne 1) {
    throw "Expected exactly one runtime-dns-egress NetworkPolicy, found $($dnsPolicies.Count)."
}
$dnsPolicy = $dnsPolicies[0]
$requiredDnsPatterns = @(
    'kubernetes.io/metadata.name:\s+kube-system',
    'k8s-app:\s+kube-dns',
    'port:\s+53\s+protocol:\s+UDP',
    'port:\s+53\s+protocol:\s+TCP'
)
foreach ($required in $requiredDnsPatterns) {
    if ($dnsPolicy -notmatch $required) {
        throw "runtime-dns-egress is missing required pattern: $required"
    }
}

Write-Host 'Runtime network policy verification passed.'
