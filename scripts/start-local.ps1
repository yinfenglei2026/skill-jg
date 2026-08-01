$ErrorActionPreference = 'Stop'

$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$environmentFile = Join-Path $repositoryRoot '.env'
$maven = Join-Path $repositoryRoot 'mvnw.cmd'
Import-Module (Join-Path $PSScriptRoot 'local-identity.psm1') -Force

if (-not (Test-Path -LiteralPath $environmentFile)) {
    throw "Local environment file was not found at $environmentFile. Copy .env.example to .env and replace its placeholder secrets."
}
if (-not (Test-Path -LiteralPath $maven)) {
    throw "Maven Wrapper was not found at $maven."
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

Get-Content $environmentFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#')) {
        $separator = $line.IndexOf('=')
        if ($separator -lt 1) {
            throw "Invalid .env entry: $line"
        }
        $name = $line.Substring(0, $separator)
        $value = $line.Substring($separator + 1)
        Set-Item -Path "Env:$name" -Value $value
    }
}

if ($env:POSTGRES_ADMIN_PASSWORD -like 'replace-with-*' -or $env:KEYCLOAK_ADMIN_PASSWORD -like 'replace-with-*') {
    throw 'Replace the placeholder passwords in .env before starting local dependencies.'
}
if ([string]::IsNullOrWhiteSpace($env:SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI)) {
    throw 'SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI must be set in .env.'
}
$postgresHostPort = if ([string]::IsNullOrWhiteSpace($env:POSTGRES_HOST_PORT)) { '5432' } else { $env:POSTGRES_HOST_PORT }
$env:POSTGRES_HOST_PORT = $postgresHostPort
Assert-GovernancePostgresPortConsistency `
    -JdbcUrl $env:SPRING_DATASOURCE_URL `
    -HostPort $postgresHostPort

& $compose @composePrefix --project-directory $repositoryRoot up -d postgres keycloak
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$issuer = $env:SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI.TrimEnd('/')
$discoveryUri = "$issuer/.well-known/openid-configuration"
for ($attempt = 1; $attempt -le 30; $attempt++) {
    try {
        Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 $discoveryUri | Out-Null
        break
    } catch {
        if ($attempt -eq 30) {
            throw "Keycloak issuer did not become ready: $discoveryUri"
        }
        Start-Sleep -Seconds 2
    }
}

& (Join-Path $PSScriptRoot 'provision-local-identities.ps1')
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& $maven -pl apps/control-plane spring-boot:run
exit $LASTEXITCODE
