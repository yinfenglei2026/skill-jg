$ErrorActionPreference = 'Stop'

$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$mavenWrapperJar = Join-Path $repositoryRoot '.mvn/wrapper/maven-wrapper.jar'
if (-not (Test-Path -LiteralPath $mavenWrapperJar)) {
    $wrapperPropertiesPath = Join-Path $repositoryRoot '.mvn/wrapper/maven-wrapper.properties'
    $wrapperProperties = Get-Content $wrapperPropertiesPath -Raw | ConvertFrom-StringData
    Invoke-WebRequest -UseBasicParsing -Uri $wrapperProperties.wrapperUrl -OutFile $mavenWrapperJar
}

& (Join-Path $PSScriptRoot 'test-build-metadata.ps1')
if (-not $?) {
    exit 1
}

& (Join-Path $PSScriptRoot 'test-ci-supply-chain.ps1')
if (-not $?) {
    exit 1
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

& java "-Dmaven.multiModuleProjectDirectory=$repositoryRoot" -classpath $mavenWrapperJar `
    org.apache.maven.wrapper.MavenWrapperMain -B -pl apps/control-plane verify
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& (Join-Path $PSScriptRoot 'test-local-identity.ps1')
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$portal = Join-Path $repositoryRoot 'apps/portal'
if (-not (Test-Path -LiteralPath (Join-Path $portal 'node_modules/vitest/vitest.mjs'))) {
    Push-Location $portal
    try {
        npm ci --ignore-scripts
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    } finally {
        Pop-Location
    }
}

Push-Location $portal
try {
    node --test test/*.test.mjs
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    node node_modules/vitest/vitest.mjs run
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    node node_modules/typescript/bin/tsc --noEmit
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    node node_modules/vite/bin/vite.js build
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}

& (Join-Path $PSScriptRoot 'test-runtime-network.ps1')
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$composeValidationValues = @{
    POSTGRES_ADMIN_USERNAME = 'verify-admin'
    POSTGRES_ADMIN_PASSWORD = 'verify-admin-password'
    GOVERNANCE_DB_USERNAME = 'verify-governance'
    GOVERNANCE_DB_PASSWORD = 'verify-governance-password'
    KEYCLOAK_DB_USERNAME = 'verify-keycloak'
    KEYCLOAK_DB_PASSWORD = 'verify-keycloak-password'
    KEYCLOAK_ADMIN_USERNAME = 'verify-admin'
    KEYCLOAK_ADMIN_PASSWORD = 'verify-keycloak-admin-password'
}
$previousComposeValues = @{}
try {
    foreach ($entry in $composeValidationValues.GetEnumerator()) {
        $previousComposeValues[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        Set-Item -Path "Env:$($entry.Key)" -Value $entry.Value
    }
    & $compose @composePrefix --project-directory $repositoryRoot config | Out-Null
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    foreach ($entry in $composeValidationValues.GetEnumerator()) {
        if ($null -eq $previousComposeValues[$entry.Key]) {
            Remove-Item -Path "Env:$($entry.Key)" -ErrorAction SilentlyContinue
        } else {
            Set-Item -Path "Env:$($entry.Key)" -Value $previousComposeValues[$entry.Key]
        }
    }
}

Get-Content (Join-Path $repositoryRoot 'infra/local/keycloak/realm-governance.json') -Raw | ConvertFrom-Json | Out-Null

Write-Host 'Verification completed successfully.'
