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
$dockerDesktopCompose = Join-Path (Split-Path (Split-Path $docker -Parent) -Parent) 'cli-plugins\docker-compose.exe'
if (Test-Path -LiteralPath $dockerDesktopCompose) {
    $compose = $dockerDesktopCompose
    $composePrefix = @()
} else {
    $compose = $docker
    $composePrefix = @('compose')
}

function Invoke-Kcadm {
    param(
        [Parameter(Mandatory)]
        [string[]]$KcadmArguments,
        [string]$StandardInput,
        [switch]$AsJson
    )

    if ($PSBoundParameters.ContainsKey('StandardInput')) {
        $encodedInput = ConvertTo-GovernanceBase64Utf8 $StandardInput
        $pipeScript = 'payload=$1; shift; printf %s "$payload" | base64 -d | /opt/keycloak/bin/kcadm.sh "$@"'
        $nativeArguments = @(
            @($composePrefix) +
                @('--project-directory', $repositoryRoot, 'exec', '-T', 'keycloak', '/bin/sh', '-c', $pipeScript, 'kcadm-stdin', $encodedInput) +
                $KcadmArguments
        )
    } else {
        $nativeArguments = @(
            @($composePrefix) + @('--project-directory', $repositoryRoot, 'exec', '-T', 'keycloak', '/opt/keycloak/bin/kcadm.sh') + $KcadmArguments
        )
    }
    $result = Invoke-GovernanceNativeCommand -FilePath $compose -ArgumentList $nativeArguments
    $output = $result.Output
    if ($result.ExitCode -ne 0) {
        $details = (@($result.ErrorOutput) + @($result.Output) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join '; '
        $operation = (@($KcadmArguments) | Select-Object -First 2) -join ' '
        throw "Keycloak administration command failed: $operation. $details"
    }
    if ($AsJson) {
        $text = ($output -join "`n").Trim()
        if ([string]::IsNullOrWhiteSpace($text)) {
            return @()
        }
        return ConvertFrom-GovernanceJsonCollection $text
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

function Set-DepartmentUserProfile {
    $profile = @(Invoke-Kcadm -KcadmArguments @('get', 'users/profile', '-r', 'governance') -AsJson)[0]
    if ($null -eq $profile) {
        throw 'Keycloak did not return the governance user profile.'
    }
    if ($profile.attributes.name -notcontains 'department') {
        $department = [pscustomobject]@{
            name = 'department'
            displayName = 'Department'
            permissions = [pscustomobject]@{ view = @('admin', 'user'); edit = @('admin') }
            multivalued = $false
        }
        $profile.attributes = @($profile.attributes) + @($department)
        $profileBody = $profile | ConvertTo-Json -Compress -Depth 12
        Invoke-Kcadm -KcadmArguments @('update', 'users/profile', '-r', 'governance', '-f', '-') -StandardInput $profileBody | Out-Null
    }
}

function Ensure-LocalClientScope {
    param([Parameter(Mandatory)][string]$ScopeName)

    $governanceScopes = @(Invoke-Kcadm -KcadmArguments @('get', 'client-scopes', '-r', 'governance', '--fields', 'id,name') -AsJson)
    $scope = @($governanceScopes | Where-Object name -eq $ScopeName)[0]
    if ($null -ne $scope) {
        return $scope
    }

    $masterScopes = @(Invoke-Kcadm -KcadmArguments @('get', 'client-scopes', '-r', 'master', '--fields', 'id,name') -AsJson)
    $templateReference = @($masterScopes | Where-Object name -eq $ScopeName)[0]
    if ($null -eq $templateReference) {
        throw "Keycloak master client scope was not found: $ScopeName"
    }
    $template = @(Invoke-Kcadm -KcadmArguments @('get', "client-scopes/$($templateReference.id)", '-r', 'master') -AsJson)[0]
    $template.PSObject.Properties.Remove('id')
    foreach ($mapper in @($template.protocolMappers)) {
        $mapper.PSObject.Properties.Remove('id')
    }
    $templateBody = $template | ConvertTo-Json -Compress -Depth 12
    Invoke-Kcadm -KcadmArguments @('create', 'client-scopes', '-r', 'governance', '-f', '-') -StandardInput $templateBody | Out-Null

    $governanceScopes = @(Invoke-Kcadm -KcadmArguments @('get', 'client-scopes', '-r', 'governance', '--fields', 'id,name') -AsJson)
    $scope = @($governanceScopes | Where-Object name -eq $ScopeName)[0]
    if ($null -eq $scope) {
        throw "Keycloak client scope could not be created: $ScopeName"
    }
    return $scope
}

function Ensure-LocalClientDefaultScopes {
    param([Parameter(Mandatory)][string]$ClientId)

    $client = @(Invoke-Kcadm -KcadmArguments @('get', 'clients', '-r', 'governance', '-q', "clientId=$ClientId", '--fields', 'id,clientId') -AsJson)[0]
    if ($null -eq $client) {
        throw "Keycloak client was not found: $ClientId"
    }
    $assignedScopes = @(Invoke-Kcadm -KcadmArguments @('get', "clients/$($client.id)/default-client-scopes", '-r', 'governance', '--fields', 'id,name') -AsJson)
    foreach ($scopeName in @('basic', 'profile', 'email', 'roles', 'governance-department')) {
        $scope = Ensure-LocalClientScope -ScopeName $scopeName
        if ($assignedScopes.id -notcontains $scope.id) {
            Invoke-Kcadm -KcadmArguments @('update', "clients/$($client.id)/default-client-scopes/$($scope.id)", '-r', 'governance', '-n') | Out-Null
        }
    }
}

function Set-LocalClient {
    param(
        [Parameter(Mandatory)][string]$ClientId,
        [Parameter(Mandatory)][bool]$StandardFlowEnabled,
        [Parameter(Mandatory)][bool]$DirectAccessGrantsEnabled,
        [hashtable]$Attributes = @{}
    )

    $matches = @(Invoke-Kcadm -KcadmArguments @('get', 'clients', '-r', 'governance', '-q', "clientId=$ClientId", '--fields', 'id,clientId') -AsJson)
    $clientBody = @{
        clientId = $ClientId
        enabled = $true
        publicClient = $true
        standardFlowEnabled = $StandardFlowEnabled
        directAccessGrantsEnabled = $DirectAccessGrantsEnabled
        attributes = $Attributes
    } | ConvertTo-Json -Compress

    if ($matches.Count -eq 0) {
        Invoke-Kcadm -KcadmArguments @('create', 'clients', '-r', 'governance', '-f', '-') -StandardInput $clientBody | Out-Null
    } else {
        Invoke-Kcadm -KcadmArguments @('update', "clients/$($matches[0].id)", '-r', 'governance', '-f', '-', '--merge') -StandardInput $clientBody | Out-Null
    }
}

Set-LocalClient -ClientId 'governance-portal' -StandardFlowEnabled $true -DirectAccessGrantsEnabled $false `
    -Attributes @{ 'pkce.code.challenge.method' = 'S256' }
Set-LocalClient -ClientId 'governance-smoke' -StandardFlowEnabled $false -DirectAccessGrantsEnabled $true
Set-DepartmentUserProfile
Ensure-LocalClientDefaultScopes -ClientId 'governance-portal'
Ensure-LocalClientDefaultScopes -ClientId 'governance-smoke'

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
    $userBody = @{
        enabled = $true
        email = $identity.Email
        emailVerified = $true
        firstName = $identity.FirstName
        lastName = $identity.LastName
        requiredActions = @()
        attributes = @{ department = @($identity.Department) }
    } | ConvertTo-Json -Compress -Depth 4
    Invoke-Kcadm -KcadmArguments @('update', "users/$userId", '-r', 'governance', '-f', '-', '--merge') -StandardInput $userBody | Out-Null
    Invoke-Kcadm -KcadmArguments @('set-password', '-r', 'governance', '--userid', $userId, '--new-password', $env:KEYCLOAK_TEST_USER_PASSWORD) | Out-Null

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
