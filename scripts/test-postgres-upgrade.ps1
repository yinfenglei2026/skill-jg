$ErrorActionPreference = 'Stop'

$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
Import-Module (Join-Path $PSScriptRoot 'local-identity.psm1') -Force
$projectName = "governance-upgrade-$PID"
if ($projectName -notmatch '^governance-upgrade-\d+$') {
    throw 'Refusing to use an invalid disposable Compose project name.'
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

$dockerInfo = Invoke-GovernanceNativeCommand -FilePath $docker -ArgumentList @('info')
if ($dockerInfo.ExitCode -ne 0) {
    throw 'Docker Engine must be running for the PostgreSQL upgrade verification.'
}

$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$listener.Start()
$postgresHostPort = $listener.LocalEndpoint.Port
$listener.Stop()

$testEnvironment = @{
    POSTGRES_HOST_PORT = "$postgresHostPort"
    POSTGRES_ADMIN_USERNAME = 'upgrade-admin'
    POSTGRES_ADMIN_PASSWORD = "upgrade-admin-$PID"
    GOVERNANCE_DB_USERNAME = 'governance-upgrade'
    GOVERNANCE_DB_PASSWORD = "governance-upgrade-$PID"
    KEYCLOAK_DB_USERNAME = 'keycloak-upgrade'
    KEYCLOAK_DB_PASSWORD = "keycloak-upgrade-$PID"
    KEYCLOAK_ADMIN_USERNAME = 'unused-upgrade-admin'
    KEYCLOAK_ADMIN_PASSWORD = "unused-upgrade-admin-$PID"
    POSTGRES_MIGRATION_TEST_URL = "jdbc:postgresql://127.0.0.1:$postgresHostPort/governance"
    POSTGRES_MIGRATION_TEST_USERNAME = 'governance-upgrade'
    POSTGRES_MIGRATION_TEST_PASSWORD = "governance-upgrade-$PID"
}
$previousEnvironment = @{}
foreach ($entry in $testEnvironment.GetEnumerator()) {
    $previousEnvironment[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
    Set-Item -Path "Env:$($entry.Key)" -Value $entry.Value
}

$testExitCode = 1
$cleanupResult = $null
try {
    & $compose @composePrefix --project-name $projectName --project-directory $repositoryRoot up -d postgres
    if ($LASTEXITCODE -ne 0) {
        throw 'Disposable PostgreSQL failed to start.'
    }
    $containerId = (& $compose @composePrefix --project-name $projectName --project-directory $repositoryRoot ps -q postgres).Trim()
    if ([string]::IsNullOrWhiteSpace($containerId)) {
        throw 'Disposable PostgreSQL container was not created.'
    }
    for ($attempt = 1; $attempt -le 40; $attempt++) {
        $health = (& $docker inspect --format '{{.State.Health.Status}}' $containerId).Trim()
        if ($health -eq 'healthy') {
            break
        }
        if ($health -eq 'unhealthy' -or $attempt -eq 40) {
            throw "Disposable PostgreSQL did not become healthy; last state: $health"
        }
        Start-Sleep -Seconds 2
    }

    $mavenWrapperJar = Join-Path $repositoryRoot '.mvn\wrapper\maven-wrapper.jar'
    if (-not (Test-Path -LiteralPath $mavenWrapperJar)) {
        $wrapperProperties = Get-Content (Join-Path $repositoryRoot '.mvn\wrapper\maven-wrapper.properties') -Raw |
            ConvertFrom-StringData
        Invoke-WebRequest -UseBasicParsing -Uri $wrapperProperties.wrapperUrl -OutFile $mavenWrapperJar
    }
    & java "-Dmaven.multiModuleProjectDirectory=$repositoryRoot" -classpath $mavenWrapperJar `
        org.apache.maven.wrapper.MavenWrapperMain -B -pl apps/control-plane -Dtest=PostgresqlMigrationTest test
    $testExitCode = $LASTEXITCODE
} finally {
    try {
        $cleanupArguments = @($composePrefix) + @(
            '--project-name', $projectName,
            '--project-directory', "$repositoryRoot",
            'down', '--volumes'
        )
        $cleanupResult = Invoke-GovernanceNativeCommand -FilePath $compose -ArgumentList $cleanupArguments
    } finally {
        foreach ($entry in $previousEnvironment.GetEnumerator()) {
            if ($null -eq $entry.Value) {
                Remove-Item -Path "Env:$($entry.Key)" -ErrorAction SilentlyContinue
            } else {
                Set-Item -Path "Env:$($entry.Key)" -Value $entry.Value
            }
        }
    }
}

if ($null -eq $cleanupResult -or $cleanupResult.ExitCode -ne 0) {
    throw 'Disposable PostgreSQL cleanup failed.'
}
if ($testExitCode -ne 0) {
    exit $testExitCode
}
Write-Host 'PostgreSQL V3-to-V4 upgrade verification passed.'
