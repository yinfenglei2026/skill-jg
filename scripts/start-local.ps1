$ErrorActionPreference = 'Stop'

$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$environmentFile = Join-Path $repositoryRoot '.env'
$maven = Join-Path $repositoryRoot 'mvnw.cmd'

if (-not (Test-Path -LiteralPath $environmentFile)) {
    throw "Local environment file was not found at $environmentFile. Copy .env.example to .env and replace its placeholder secrets."
}
if (-not (Test-Path -LiteralPath $maven)) {
    throw "Maven Wrapper was not found at $maven."
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
