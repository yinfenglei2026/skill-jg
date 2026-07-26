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
foreach ($identity in $identities) {
    if ([string]::IsNullOrWhiteSpace($identity.Email) -or
        [string]::IsNullOrWhiteSpace($identity.FirstName) -or
        [string]::IsNullOrWhiteSpace($identity.LastName)) {
        throw 'Synthetic identities must include the profile fields required by Keycloak.'
    }
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

$encodedJson = ConvertTo-GovernanceBase64Utf8 '{"message":"expected utf8"}'
$decodedJson = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encodedJson))
if ($decodedJson -ne '{"message":"expected utf8"}') {
    throw 'Base64 helper must preserve UTF-8 JSON without a byte-order mark.'
}

$jsonItems = @(ConvertFrom-GovernanceJsonCollection '[{"id":"client-id"}]')
if ($jsonItems.Count -ne 1 -or $jsonItems[0].id -ne 'client-id' -or $jsonItems[0] -is [array]) {
    throw 'JSON collection helper must return resource objects without a nested array.'
}

$nativeResult = Invoke-GovernanceNativeCommand -FilePath (Join-Path $PSHOME 'powershell.exe') -ArgumentList @(
    '-NoProfile',
    '-Command',
    '[Console]::Error.WriteLine(''expected stderr''); [Console]::Out.Write(''expected stdout''); exit 0'
)
if ($nativeResult.ExitCode -ne 0 -or
    ($nativeResult.Output -join "`n") -notmatch 'expected stdout' -or
    ($nativeResult.Output -join "`n") -match 'expected stderr' -or
    ($nativeResult.ErrorOutput -join "`n") -notmatch 'expected stderr') {
    throw 'Native command helper must separate stderr from stdout and use the process exit code.'
}

$provisioningScript = Get-Content (Join-Path $PSScriptRoot 'provision-local-identities.ps1') -Raw
if ($provisioningScript -notmatch "'--userid'" -or
    $provisioningScript -match "(?m)^.*set-password.*'--uid'.*$" -or
    $provisioningScript -match "(?m)^.*set-password.*'--temporary=false'.*$") {
    throw 'Identity provisioning must use the Keycloak 26 set-password options.'
}

$realm = Get-Content (Join-Path $PSScriptRoot '..\infra\local\keycloak\realm-governance.json') -Raw | ConvertFrom-Json
if ($realm.userProfile.attributes.name -notcontains 'department' -or
    $realm.defaultDefaultClientScopes -notcontains 'roles' -or
    $provisioningScript -notmatch 'users/profile' -or
    $provisioningScript -notmatch 'default-client-scopes') {
    throw 'Identity provisioning must converge the department profile and default client scopes.'
}

Write-Host 'Local identity helper tests passed.'
