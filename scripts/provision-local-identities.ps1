$ErrorActionPreference = 'Stop'

Import-Module (Join-Path $PSScriptRoot 'local-identity.psm1') -Force

$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
foreach ($name in @('KEYCLOAK_ADMIN_USERNAME', 'KEYCLOAK_ADMIN_PASSWORD', 'KEYCLOAK_TEST_USER_PASSWORD')) {
    $value = [Environment]::GetEnvironmentVariable($name, 'Process')
    if ([string]::IsNullOrWhiteSpace($value) -or $value -like 'replace-with-*') {
        throw "$name must be set to a non-placeholder local value."
    }
}

$docker = (Get-Command docker -ErrorAction Stop).Source
$compose = $docker
$composePrefix = @('compose')
$originalErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    & $docker compose version *> $null
    $standardComposeAvailable = $LASTEXITCODE -eq 0
} finally {
    $ErrorActionPreference = $originalErrorActionPreference
}
if (-not $standardComposeAvailable) {
    $dockerDesktopCompose = Join-Path (Split-Path (Split-Path $docker -Parent) -Parent) 'cli-plugins\docker-compose.exe'
    if (-not (Test-Path -LiteralPath $dockerDesktopCompose)) {
        throw 'Docker Compose was not found as a CLI plugin or Docker Desktop executable.'
    }
    $compose = $dockerDesktopCompose
    $composePrefix = @()
}

function Invoke-Kcadm {
    param(
        [Parameter(Mandatory)]
        [string[]]$KcadmArguments,
        [switch]$AsJson
    )

    $output = & $compose @composePrefix --project-directory $repositoryRoot exec -T keycloak /opt/keycloak/bin/kcadm.sh @KcadmArguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Keycloak administration command failed: $($KcadmArguments[0])"
    }
    if ($AsJson) {
        $text = ($output -join "`n").Trim()
        if ([string]::IsNullOrWhiteSpace($text)) {
            return @()
        }
        return @($text | ConvertFrom-Json)
    }
    return $output
}

Invoke-Kcadm -KcadmArguments @(
    'config', 'credentials',
    '--server', 'http://localhost:8080',
    '--realm', 'master',
    '--user', $env:KEYCLOAK_ADMIN_USERNAME,
    '--password', $env:KEYCLOAK_ADMIN_PASSWORD
) | Out-Null

function Set-LocalClient {
    param(
        [Parameter(Mandatory)][string]$ClientId,
        [Parameter(Mandatory)][bool]$StandardFlowEnabled,
        [Parameter(Mandatory)][bool]$DirectAccessGrantsEnabled,
        [hashtable]$Attributes = @{}
    )

    $matches = @(Invoke-Kcadm -KcadmArguments @('get', 'clients', '-r', 'governance', '-q', "clientId=$ClientId", '--fields', 'id,clientId') -AsJson)
    $settings = @(
        '-s', "clientId=$ClientId",
        '-s', 'enabled=true',
        '-s', 'publicClient=true',
        '-s', "standardFlowEnabled=$($StandardFlowEnabled.ToString().ToLowerInvariant())",
        '-s', "directAccessGrantsEnabled=$($DirectAccessGrantsEnabled.ToString().ToLowerInvariant())"
    )
    if ($Attributes.Count -gt 0) {
        $settings += @('-s', "attributes=$($Attributes | ConvertTo-Json -Compress)")
    }

    if ($matches.Count -eq 0) {
        Invoke-Kcadm -KcadmArguments (@('create', 'clients', '-r', 'governance') + $settings) | Out-Null
    } else {
        Invoke-Kcadm -KcadmArguments (@('update', "clients/$($matches[0].id)", '-r', 'governance') + $settings) | Out-Null
    }
}

Set-LocalClient -ClientId 'governance-portal' -StandardFlowEnabled $true -DirectAccessGrantsEnabled $false `
    -Attributes @{ 'pkce.code.challenge.method' = 'S256' }
Set-LocalClient -ClientId 'governance-smoke' -StandardFlowEnabled $false -DirectAccessGrantsEnabled $true

$governanceRoles = @('owner', 'reviewer', 'approver', 'operator', 'read-only')
foreach ($identity in Get-GovernanceSyntheticIdentities) {
    $users = @(Invoke-Kcadm -KcadmArguments @('get', 'users', '-r', 'governance', '-q', "username=$($identity.Username)", '--fields', 'id,username') -AsJson)
    if ($users.Count -eq 0) {
        Invoke-Kcadm -KcadmArguments @('create', 'users', '-r', 'governance', '-s', "username=$($identity.Username)", '-s', 'enabled=true') | Out-Null
        $users = @(Invoke-Kcadm -KcadmArguments @('get', 'users', '-r', 'governance', '-q', "username=$($identity.Username)", '--fields', 'id,username') -AsJson)
    }
    if ($users.Count -ne 1) {
        throw "Expected exactly one Keycloak user for $($identity.Username)."
    }

    $userId = $users[0].id
    $departmentAttributes = @{ department = @($identity.Department) } | ConvertTo-Json -Compress
    Invoke-Kcadm -KcadmArguments @('update', "users/$userId", '-r', 'governance', '-s', 'enabled=true', '-s', "attributes=$departmentAttributes") | Out-Null
    Invoke-Kcadm -KcadmArguments @('set-password', '-r', 'governance', '--uid', $userId, '--new-password', $env:KEYCLOAK_TEST_USER_PASSWORD, '--temporary=false') | Out-Null

    $assignedRoles = @(Invoke-Kcadm -KcadmArguments @('get', "users/$userId/role-mappings/realm/composite", '-r', 'governance') -AsJson)
    foreach ($role in $governanceRoles) {
        if ($role -ne $identity.Role -and $assignedRoles.name -contains $role) {
            Invoke-Kcadm -KcadmArguments @('remove-roles', '-r', 'governance', '--uid', $userId, '--rolename', $role) | Out-Null
        }
    }
    if ($assignedRoles.name -notcontains $identity.Role) {
        Invoke-Kcadm -KcadmArguments @('add-roles', '-r', 'governance', '--uid', $userId, '--rolename', $identity.Role) | Out-Null
    }
}

Write-Host 'Synthetic local governance identities are provisioned.'
