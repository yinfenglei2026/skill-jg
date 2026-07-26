param(
    [string]$Issuer = $env:SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI,
    [string]$ApiBaseUrl = 'http://127.0.0.1:8080/api/v1',
    [string]$Username = 'owner.customer.test',
    [string]$Password = $env:KEYCLOAK_TEST_USER_PASSWORD,
    [string]$ExpectedDepartment = 'customer-operations',
    [string]$ExpectedRole = 'owner'
)

$ErrorActionPreference = 'Stop'

Import-Module (Join-Path $PSScriptRoot 'local-identity.psm1') -Force

if ([string]::IsNullOrWhiteSpace($Issuer)) {
    throw 'The local Keycloak issuer must be provided.'
}
if ([string]::IsNullOrWhiteSpace($Password) -or $Password -like 'replace-with-*') {
    throw 'KEYCLOAK_TEST_USER_PASSWORD must be set to a non-placeholder local value.'
}

$tokenResponse = Invoke-RestMethod -Method Post -Uri "$($Issuer.TrimEnd('/'))/protocol/openid-connect/token" `
    -ContentType 'application/x-www-form-urlencoded' `
    -Body @{
        client_id = 'governance-smoke'
        grant_type = 'password'
        username = $Username
        password = $Password
    }
if ([string]::IsNullOrWhiteSpace($tokenResponse.access_token)) {
    throw 'Local Keycloak did not return an access token.'
}

$claims = Get-JwtPayload -AccessToken $tokenResponse.access_token
Assert-GovernanceJwtClaims -Claims $claims -ExpectedDepartment $ExpectedDepartment -ExpectedRole $ExpectedRole

$headers = @{ Authorization = "Bearer $($tokenResponse.access_token)"; Accept = 'application/json' }
$response = Invoke-WebRequest -UseBasicParsing -Method Get -Uri "$($ApiBaseUrl.TrimEnd('/'))/capabilities" -Headers $headers
if ($response.StatusCode -ne 200) {
    throw 'Authenticated governance catalog request did not return HTTP 200.'
}

Write-Host 'Local JWT authentication smoke test passed.'
