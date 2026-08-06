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

    $entries = [System.Collections.Generic.List[string]]::new()
    foreach ($file in Get-ChildItem -LiteralPath $PortalDistPath -File -Recurse) {
        $relativePath = $file.FullName.Substring($PortalDistPath.Length).TrimStart([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar).Replace('\', '/')
        [void]$entries.Add(("{0}{1}{2}`n" -f $relativePath, [char]0, (Get-FileSha256 -Path $file.FullName)))
    }
    $entries.Sort([StringComparer]::Ordinal)
    $canonicalTree = ($entries -join '')
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

function Get-ArtifactMetadata {
    param(
        [Parameter(Mandatory)]
        [object[]]$Artifacts,
        [Parameter(Mandatory)]
        [string]$Name
    )

    $matches = @($Artifacts | Where-Object name -eq $Name)
    if ($matches.Count -ne 1) {
        throw "Build metadata must contain exactly one '$Name' artifact."
    }
    return $matches[0]
}

$repositoryRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$writerPath = Join-Path $PSScriptRoot 'write-build-metadata.ps1'
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) "build-metadata-contract-$PID-$([Guid]::NewGuid().ToString('N'))"

try {
    $portalDistPath = Join-Path $fixtureRoot 'portal-dist'
    $portalAssetsPath = Join-Path $portalDistPath 'assets'
    New-Item -ItemType Directory -Path $portalAssetsPath -Force | Out-Null

    $jarPath = Join-Path $fixtureRoot 'control-plane.jar'
    $controlPlaneSbomPath = Join-Path $fixtureRoot 'control-plane.cdx.json'
    $portalSbomPath = Join-Path $fixtureRoot 'portal.cdx.json'
    [IO.File]::WriteAllBytes($jarPath, [byte[]](0x50, 0x4b, 0x03, 0x04, 0x63, 0x6f, 0x6e, 0x74, 0x72, 0x61, 0x63, 0x74))
    [IO.File]::WriteAllText($controlPlaneSbomPath, '{"bomFormat":"CycloneDX","specVersion":"1.6"}', [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText($portalSbomPath, '{"bomFormat":"CycloneDX","specVersion":"1.6","components":[]}', [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $portalDistPath 'index.html'), '<main>portal contract</main>', [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $portalAssetsPath 'app.js'), 'console.log("portal contract");', [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $portalAssetsPath 'Z.js'), 'console.log("uppercase contract");', [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $portalAssetsPath 'a.js'), 'console.log("lowercase contract");', [Text.UTF8Encoding]::new($false))

    if (-not (Test-Path -LiteralPath $writerPath)) {
        throw 'Build metadata writer is missing: scripts/write-build-metadata.ps1'
    }

    $commitSha = '0123456789abcdef0123456789abcdef01234567'
    $firstOutputPath = Join-Path $fixtureRoot 'build-metadata-first.json'
    $secondOutputPath = Join-Path $fixtureRoot 'build-metadata-second.json'
    & $writerPath `
        -CommitSha $commitSha `
        -JarPath $jarPath `
        -ControlPlaneSbomPath $controlPlaneSbomPath `
        -PortalSbomPath $portalSbomPath `
        -PortalDistPath $portalDistPath `
        -OutputPath $firstOutputPath
    & $writerPath `
        -CommitSha $commitSha `
        -JarPath $jarPath `
        -ControlPlaneSbomPath $controlPlaneSbomPath `
        -PortalSbomPath $portalSbomPath `
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
    $artifacts = @($metadata.artifacts)
    Assert-ArtifactMetadata -Artifact (Get-ArtifactMetadata -Artifacts $artifacts -Name 'control-plane-jar') -Name 'Control-plane JAR metadata' -Path $jarPath
    Assert-ArtifactMetadata -Artifact (Get-ArtifactMetadata -Artifacts $artifacts -Name 'control-plane-sbom') -Name 'Control-plane SBOM metadata' -Path $controlPlaneSbomPath
    Assert-ArtifactMetadata -Artifact (Get-ArtifactMetadata -Artifacts $artifacts -Name 'portal-sbom') -Name 'Portal SBOM metadata' -Path $portalSbomPath

    $portalMetadata = Get-ArtifactMetadata -Artifacts $artifacts -Name 'portal-dist'
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
            -ControlPlaneSbomPath $controlPlaneSbomPath `
            -PortalSbomPath $portalSbomPath `
            -PortalDistPath $portalDistPath `
            -OutputPath (Join-Path $fixtureRoot 'missing-artifact-metadata.json')
    } catch {
        $missingArtifactError = $_.Exception.Message
    }
    if ([string]::IsNullOrWhiteSpace($missingArtifactError)) {
        throw 'Build metadata writer must fail when an input artifact is missing.'
    }

    foreach ($invalidCommitSha in @('', '0123456789ABCDEF0123456789ABCDEF01234567')) {
        $invalidCommitError = $null
        try {
            & $writerPath `
                -CommitSha $invalidCommitSha `
                -JarPath $jarPath `
                -ControlPlaneSbomPath $controlPlaneSbomPath `
                -PortalSbomPath $portalSbomPath `
                -PortalDistPath $portalDistPath `
                -OutputPath (Join-Path $fixtureRoot 'invalid-commit-metadata.json')
        } catch {
            $invalidCommitError = $_.Exception.Message
        }
        if ([string]::IsNullOrWhiteSpace($invalidCommitError)) {
            throw 'Build metadata writer must reject empty, uppercase, or malformed commit SHA inputs.'
        }
    }

    Write-Host 'Build metadata contract tests passed.'
} finally {
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}
