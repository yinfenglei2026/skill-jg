$ErrorActionPreference = 'Stop'

Import-Module (Join-Path $PSScriptRoot 'local-identity.psm1') -Force

$identities = @(Get-GovernanceSyntheticIdentities)
if ($identities.Count -ne 6) {
    throw 'Expected six synthetic identities.'
}
$billingOwner = $identities | Where-Object Username -eq 'owner.billing.test'
if ($billingOwner.Department -ne 'billing' -or $billingOwner.Role -ne 'owner') {
    throw 'Billing isolation identity is missing.'
}

$payload = @{
    sub = 'owner.customer.test'
    department = 'customer-operations'
    realm_access = @{ roles = @('owner') }
}
$json = $payload | ConvertTo-Json -Compress -Depth 4
$encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$claims = Get-JwtPayload -AccessToken "header.$encoded.signature"
Assert-GovernanceJwtClaims -Claims $claims -ExpectedDepartment 'customer-operations' -ExpectedRole 'owner'

Write-Host 'Local identity helper tests passed.'
