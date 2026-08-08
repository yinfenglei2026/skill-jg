$ErrorActionPreference = 'Stop'

$installerPath = Join-Path $PSScriptRoot 'install-cosign.ps1'
if (-not (Test-Path -LiteralPath $installerPath)) {
    throw 'Cosign installer is missing: scripts/install-cosign.ps1'
}

$installer = Get-Content -LiteralPath $installerPath -Raw
$requirements = @(
    @{ Name = 'pinned Cosign version'; Pattern = '(?m)^\s*\$cosignVersion\s*=\s*''3\.0\.6''\s*$' }
    @{ Name = 'official release URL'; Pattern = 'https://github\.com/sigstore/cosign/releases/download/v\$cosignVersion/' }
    @{ Name = 'Linux amd64 asset'; Pattern = 'cosign-linux-amd64' }
    @{ Name = 'Windows amd64 asset'; Pattern = 'cosign-windows-amd64\.exe' }
    @{ Name = 'Linux SHA-256'; Pattern = 'c956e5dfcac53d52bcf058360d579472f0c1d2d9b69f55209e256fe7783f4c74' }
    @{ Name = 'Windows SHA-256'; Pattern = '9b85a88ebff2d9dd30ff4984a6f61f2cedc232dd87d81fa7f2ff3c0ed96c241c' }
    @{ Name = 'SHA-256 validation'; Pattern = 'Get-FileHash[^\r\n]+SHA256' }
    @{ Name = 'temporary cleanup'; Pattern = '(?s)finally\s*\{.*Remove-Item' }
    @{ Name = 'version execution'; Pattern = '(?i)&\s*\$destination\s+version' }
)
foreach ($requirement in $requirements) {
    if ($installer -notmatch $requirement.Pattern) {
        throw "Cosign installer lacks $($requirement.Name)."
    }
}

foreach ($forbidden in @('latest', '--allow-http-registry', '--allow-insecure-registry',
        '--insecure-ignore-tlog', '--registry-password', '--registry-token')) {
    if ($installer -match [regex]::Escape($forbidden)) {
        throw "Cosign installer contains forbidden token: $forbidden"
    }
}

Write-Host 'Cosign installer contract passed.'
