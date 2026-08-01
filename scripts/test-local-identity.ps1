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

$missingSubjectError = $null
try {
    Assert-GovernanceJwtClaims `
        -Claims ([pscustomobject]@{
            department = 'customer-operations'
            realm_access = [pscustomobject]@{ roles = @('owner') }
        }) `
        -ExpectedDepartment 'customer-operations' `
        -ExpectedRole 'owner'
} catch {
    $missingSubjectError = $_.Exception.Message
}
if ($missingSubjectError -notmatch 'subject') {
    throw 'JWT claim validation must reject access tokens without a subject.'
}

Assert-GovernancePostgresPortConsistency `
    -JdbcUrl 'jdbc:postgresql://localhost:15432/governance' `
    -HostPort '15432'
Assert-GovernancePostgresPortConsistency `
    -JdbcUrl 'jdbc:postgresql://localhost/governance' `
    -HostPort '5432'
$portMismatchError = $null
try {
    Assert-GovernancePostgresPortConsistency `
        -JdbcUrl 'jdbc:postgresql://localhost:15432/governance' `
        -HostPort '5432'
} catch {
    $portMismatchError = $_.Exception.Message
}
if ($portMismatchError -notmatch 'SPRING_DATASOURCE_URL' -or
    $portMismatchError -notmatch 'POSTGRES_HOST_PORT') {
    throw 'PostgreSQL port mismatch must identify both local settings.'
}

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
if ($provisioningScript -match '\$standardComposeAvailable' -or
    $provisioningScript -notmatch 'if \(Test-Path -LiteralPath \$dockerDesktopCompose\)') {
    throw 'Identity provisioning must prefer the Docker Desktop Compose executable when it exists.'
}

$realm = Get-Content (Join-Path $PSScriptRoot '..\infra\local\keycloak\realm-governance.json') -Raw | ConvertFrom-Json
if ($realm.PSObject.Properties.Name -contains 'userProfile' -or
    $realm.defaultDefaultClientScopes -notcontains 'roles' -or
    $provisioningScript -notmatch 'users/profile' -or
    $provisioningScript -notmatch 'default-client-scopes' -or
    $provisioningScript -notmatch "@\('basic', 'profile', 'email', 'roles', 'governance-department'\)") {
    throw 'Realm import must remain Keycloak-compatible while provisioning converges profiles and scopes.'
}

$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$composeDeclaration = Get-Content (Join-Path $repositoryRoot 'compose.yaml') -Raw
$exampleEnvironment = Get-Content (Join-Path $repositoryRoot '.env.example') -Raw
if ($composeDeclaration -notmatch [regex]::Escape('${POSTGRES_HOST_PORT:-5432}') -or
    $exampleEnvironment -notmatch '(?m)^POSTGRES_HOST_PORT=5432$' -or
    $exampleEnvironment -notmatch '(?m)^SPRING_PROFILES_ACTIVE=local$') {
    throw 'Local PostgreSQL must expose a configurable host port with a documented default.'
}
if ($exampleEnvironment -notmatch '(?m)^VITE_GOVERNANCE_OIDC_AUTHORITY=http://127\.0\.0\.1:8081/realms/governance$' -or
    $exampleEnvironment -notmatch '(?m)^SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=http://127\.0\.0\.1:8081/realms/governance$') {
    throw 'Local Keycloak URLs must use the IPv4 loopback address bound by Compose.'
}

$postgresUpgradeScriptPath = Join-Path $PSScriptRoot 'test-postgres-upgrade.ps1'
if (-not (Test-Path -LiteralPath $postgresUpgradeScriptPath)) {
    throw 'The disposable PostgreSQL upgrade verification script is missing.'
}
$postgresUpgradeScript = Get-Content $postgresUpgradeScriptPath -Raw
if ($postgresUpgradeScript -notmatch 'governance-upgrade-\$PID' -or
    $postgresUpgradeScript -notmatch '--project-name' -or
    $postgresUpgradeScript -notmatch 'down.*--volumes' -or
    $postgresUpgradeScript -match 'if \(\$started\)' -or
    $postgresUpgradeScript -match '& \$docker info') {
    throw 'PostgreSQL upgrade verification must isolate and clean its Compose project.'
}

$startLocalScript = Get-Content (Join-Path $PSScriptRoot 'start-local.ps1') -Raw
if ($startLocalScript -match '\$standardComposeAvailable' -or
    $startLocalScript -notmatch 'if \(Test-Path -LiteralPath \$dockerDesktopCompose\)') {
    throw 'Local startup must prefer the Docker Desktop Compose executable when it exists.'
}

$postgresInitScriptPath = Join-Path $repositoryRoot 'infra\local\postgres\init\01-create-databases.sh'
$postgresInitBytes = [IO.File]::ReadAllBytes($postgresInitScriptPath)
if ($postgresInitBytes -contains 13) {
    throw 'Linux container initialization scripts must use LF line endings.'
}
$gitAttributesPath = Join-Path $repositoryRoot '.gitattributes'
if (-not (Test-Path -LiteralPath $gitAttributesPath) -or
    (Get-Content $gitAttributesPath -Raw) -notmatch '(?m)^\*\.sh text eol=lf$') {
    throw 'Git attributes must preserve LF line endings for shell scripts.'
}

Write-Host 'Local identity helper tests passed.'
