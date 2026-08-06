$ErrorActionPreference = 'Stop'

function Get-FileSha256 {
    param(
        [Parameter(Mandatory)]
        [string]$Path
    )

    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $hash = $sha256.ComputeHash([IO.File]::ReadAllBytes($Path))
        return ([BitConverter]::ToString($hash)).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
}

function Get-PortalTreeSha256 {
    param(
        [Parameter(Mandatory)]
        [string]$PortalDistPath
    )

    $entries = foreach ($file in Get-ChildItem -LiteralPath $PortalDistPath -File -Recurse) {
        $relativePath = $file.FullName.Substring($PortalDistPath.Length).TrimStart([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar).Replace('\', '/')
        "{0}`n{1}`n{2}`n" -f $relativePath, (Get-FileSha256 -Path $file.FullName), $file.Length
    }
    $canonicalTree = (($entries | Sort-Object) -join '')
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $hash = $sha256.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonicalTree))
        return ([BitConverter]::ToString($hash)).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
}

function Assert-ArtifactMetadata {
    param(
        [Parameter(Mandatory)]
        $Artifact,
        [Parameter(Mandatory)]
        [string]$Name,
        [Parameter(Mandatory)]
        [string]$Path
    )

    if ($null -eq $Artifact -or $Artifact.bytes -ne (Get-Item -LiteralPath $Path).Length) {
        throw "$Name must include the artifact byte count."
    }
    $expectedSha256 = Get-FileSha256 -Path $Path
    if ($Artifact.sha256 -notmatch '^[0-9a-f]{64}$' -or $Artifact.sha256 -ne $expectedSha256) {
        throw "$Name must include the artifact SHA-256."
    }
}

$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$writerPath = Join-Path $PSScriptRoot 'write-build-metadata.ps1'
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) "build-metadata-contract-$PID-$([Guid]::NewGuid().ToString('N'))"

try {
    $portalDistPath = Join-Path $fixtureRoot 'portal-dist'
    $portalAssetsPath = Join-Path $portalDistPath 'assets'
    New-Item -ItemType Directory -Path $portalAssetsPath -Force | Out-Null

    $jarPath = Join-Path $fixtureRoot 'control-plane.jar'
    $sbomPath = Join-Path $fixtureRoot 'control-plane.cdx.json'
    [IO.File]::WriteAllBytes($jarPath, [byte[]](0x50, 0x4b, 0x03, 0x04, 0x63, 0x6f, 0x6e, 0x74, 0x72, 0x61, 0x63, 0x74))
    [IO.File]::WriteAllText($sbomPath, '{"bomFormat":"CycloneDX","specVersion":"1.6"}', [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $portalDistPath 'index.html'), '<main>portal contract</main>', [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $portalAssetsPath 'app.js'), 'console.log("portal contract");', [Text.UTF8Encoding]::new($false))

    if (-not (Test-Path -LiteralPath $writerPath)) {
        throw 'Build metadata writer is missing: scripts/write-build-metadata.ps1'
    }

    $commitSha = '0123456789abcdef0123456789abcdef01234567'
    $firstOutputPath = Join-Path $fixtureRoot 'build-metadata-first.json'
    $secondOutputPath = Join-Path $fixtureRoot 'build-metadata-second.json'
    & $writerPath `
        -CommitSha $commitSha `
        -JarPath $jarPath `
        -SbomPath $sbomPath `
        -PortalDistPath $portalDistPath `
        -OutputPath $firstOutputPath
    & $writerPath `
        -CommitSha $commitSha `
        -JarPath $jarPath `
        -SbomPath $sbomPath `
        -PortalDistPath $portalDistPath `
        -OutputPath $secondOutputPath

    $firstBytes = [IO.File]::ReadAllBytes($firstOutputPath)
    $secondBytes = [IO.File]::ReadAllBytes($secondOutputPath)
    if (-not [Collections.StructuralComparisons]::StructuralEqualityComparer.Equals($firstBytes, $secondBytes)) {
        throw 'Build metadata JSON must be byte-identical for identical inputs.'
    }

    $metadata = [IO.File]::ReadAllText($firstOutputPath, [Text.Encoding]::UTF8) | ConvertFrom-Json
    if ($metadata.schemaVersion -ne 1) {
        throw 'Build metadata must declare schemaVersion 1.'
    }
    if ($metadata.commitSha -notmatch '^[0-9a-f]{40}$' -or $metadata.commitSha -ne $commitSha) {
        throw 'Build metadata must include the validated 40-character commit SHA.'
    }
    Assert-ArtifactMetadata -Artifact $metadata.artifacts.controlPlaneJar -Name 'Control-plane JAR metadata' -Path $jarPath
    Assert-ArtifactMetadata -Artifact $metadata.artifacts.controlPlaneSbom -Name 'Control-plane SBOM metadata' -Path $sbomPath

    $portalMetadata = $metadata.artifacts.portalDist
    $expectedPortalBytes = @(Get-ChildItem -LiteralPath $portalDistPath -File -Recurse | ForEach-Object Length | Measure-Object -Sum).Sum
    $expectedTreeSha256 = Get-PortalTreeSha256 -PortalDistPath $portalDistPath
    if ($null -eq $portalMetadata -or $portalMetadata.bytes -ne $expectedPortalBytes) {
        throw 'Portal distribution metadata must include the total byte count.'
    }
    if ($portalMetadata.treeSha256 -notmatch '^[0-9a-f]{64}$' -or $portalMetadata.treeSha256 -ne $expectedTreeSha256) {
        throw 'Portal distribution metadata must include the stable tree SHA-256.'
    }

    $missingArtifactError = $null
    try {
        & $writerPath `
            -CommitSha $commitSha `
            -JarPath (Join-Path $fixtureRoot 'missing.jar') `
            -SbomPath $sbomPath `
            -PortalDistPath $portalDistPath `
            -OutputPath (Join-Path $fixtureRoot 'missing-artifact-metadata.json')
    } catch {
        $missingArtifactError = $_.Exception.Message
    }
    if ([string]::IsNullOrWhiteSpace($missingArtifactError)) {
        throw 'Build metadata writer must fail when an input artifact is missing.'
    }

    Write-Host 'Build metadata contract tests passed.'
} finally {
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}
