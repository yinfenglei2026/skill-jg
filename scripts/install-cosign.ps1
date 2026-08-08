param(
    [Parameter(Mandatory = $true)]
    [string]$InstallDirectory
)

$ErrorActionPreference = 'Stop'
$cosignVersion = '3.0.6'
$linuxSha256 = 'c956e5dfcac53d52bcf058360d579472f0c1d2d9b69f55209e256fe7783f4c74'
$windowsSha256 = '9b85a88ebff2d9dd30ff4984a6f61f2cedc232dd87d81fa7f2ff3c0ed96c241c'

$architecture = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture
if ($architecture -ne [System.Runtime.InteropServices.Architecture]::X64) {
    throw "Unsupported Cosign architecture: $architecture"
}

if ([System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
        [System.Runtime.InteropServices.OSPlatform]::Windows)) {
    $asset = 'cosign-windows-amd64.exe'
    $expectedSha256 = $windowsSha256
    $destinationName = 'cosign.exe'
} elseif ([System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
        [System.Runtime.InteropServices.OSPlatform]::Linux)) {
    $asset = 'cosign-linux-amd64'
    $expectedSha256 = $linuxSha256
    $destinationName = 'cosign'
} else {
    throw 'Cosign installer supports only Windows and Linux on amd64.'
}

$resolvedInstallDirectory = [System.IO.Path]::GetFullPath($InstallDirectory)
$temporaryDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ("cosign-install-" + [guid]::NewGuid())
$download = Join-Path $temporaryDirectory $asset
$destination = Join-Path $resolvedInstallDirectory $destinationName
$url = "https://github.com/sigstore/cosign/releases/download/v$cosignVersion/$asset"

try {
    New-Item -ItemType Directory -Path $temporaryDirectory | Out-Null
    New-Item -ItemType Directory -Path $resolvedInstallDirectory -Force | Out-Null
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $download
    $actualSha256 = (Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualSha256 -ne $expectedSha256) {
        throw "Cosign SHA-256 mismatch for $asset."
    }
    if ($destinationName -eq 'cosign') {
        & chmod 0755 $download
        if ($LASTEXITCODE -ne 0) {
            throw 'Could not mark Cosign executable.'
        }
    }
    Move-Item -LiteralPath $download -Destination $destination -Force
    & $destination version
    if ($LASTEXITCODE -ne 0) {
        throw 'Installed Cosign failed its version check.'
    }
    Write-Output $destination
} finally {
    if (Test-Path -LiteralPath $temporaryDirectory) {
        Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force
    }
}
