param(
    [Parameter(Mandatory)]
    [string]$CommitSha,
    [Parameter(Mandatory)]
    [string]$JarPath,
    [Parameter(Mandatory)]
    [string]$ControlPlaneSbomPath,
    [Parameter(Mandatory)]
    [string]$PortalSbomPath,
    [Parameter(Mandatory)]
    [string]$PortalDistPath,
    [Parameter(Mandatory)]
    [string]$OutputPath,
    [string]$RepositoryRoot = (Join-Path $PSScriptRoot '..'),
    [string]$Ref = 'local',
    [string]$Workflow = 'local',
    [string]$RunId = 'local',
    [long]$SourceDateEpoch = 0,
    [string]$JavaVersion = 'local',
    [string]$MavenVersion = 'local',
    [string]$NodeVersion = 'local',
    [string]$NpmVersion = 'local'
)

$ErrorActionPreference = 'Stop'

function Assert-NonEmpty {
    param(
        [Parameter(Mandatory)]
        [string]$Value,
        [Parameter(Mandatory)]
        [string]$Name
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "$Name is required."
    }
}

function Resolve-ExistingFile {
    param(
        [Parameter(Mandatory)]
        [string]$Path,
        [Parameter(Mandatory)]
        [string]$Name
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Name is missing: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Get-FileSha256 {
    param(
        [Parameter(Mandatory)]
        [string]$Path
    )

    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-ArtifactPath {
    param(
        [Parameter(Mandatory)]
        [string]$Path,
        [Parameter(Mandatory)]
        [string]$Root
    )

    $fullPath = (Resolve-Path -LiteralPath $Path).Path
    $fullRoot = (Resolve-Path -LiteralPath $Root).Path.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    if ($fullPath.StartsWith($fullRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        return $fullPath.Substring($fullRoot.Length + 1).Replace('\', '/')
    }
    return [IO.Path]::GetFileName($fullPath)
}

function New-FileArtifact {
    param(
        [Parameter(Mandatory)]
        [string]$Name,
        [Parameter(Mandatory)]
        [string]$Path,
        [Parameter(Mandatory)]
        [string]$Root
    )

    $file = Get-Item -LiteralPath $Path
    return [pscustomobject][ordered]@{
        name = $Name
        path = Get-ArtifactPath -Path $file.FullName -Root $Root
        sha256 = Get-FileSha256 -Path $file.FullName
        bytes = [int64]$file.Length
    }
}

function New-PortalTreeArtifact {
    param(
        [Parameter(Mandatory)]
        [string]$Path,
        [Parameter(Mandatory)]
        [string]$Root
    )

    $fullPath = (Resolve-Path -LiteralPath $Path).Path
    $entries = @(
        foreach ($file in Get-ChildItem -LiteralPath $fullPath -File -Recurse) {
        $relativePath = $file.FullName.Substring($fullPath.Length).TrimStart([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar).Replace('\', '/')
        [pscustomobject]@{
            relativePath = $relativePath
            sha256 = Get-FileSha256 -Path $file.FullName
            bytes = [int64]$file.Length
        }
        }
    )
    $orderedEntries = [System.Collections.Generic.List[object]]::new()
    foreach ($entry in $entries) {
        [void]$orderedEntries.Add($entry)
    }
    $orderedEntries.Sort([System.Comparison[object]]{
            param($left, $right)
            [String]::Compare($left.relativePath, $right.relativePath, [StringComparison]::Ordinal)
        })
    $canonicalTree = (($orderedEntries | ForEach-Object { "{0}{1}{2}`n" -f $_.relativePath, [char]0, $_.sha256 }) -join '')
    $treeSha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $treeHash = $treeSha256.ComputeHash([Text.Encoding]::UTF8.GetBytes($canonicalTree))
    } finally {
        $treeSha256.Dispose()
    }
    return [pscustomobject][ordered]@{
        name = 'portal-dist'
        path = Get-ArtifactPath -Path $fullPath -Root $Root
        treeSha256 = ([BitConverter]::ToString($treeHash)).Replace('-', '').ToLowerInvariant()
        bytes = [int64](($entries | Measure-Object bytes -Sum).Sum)
    }
}

if ($CommitSha -cnotmatch '^[0-9a-f]{40}$') {
    throw 'CommitSha must be a 40-character lowercase hexadecimal SHA.'
}
if ($SourceDateEpoch -lt 0) {
    throw 'SourceDateEpoch must be nonnegative.'
}
foreach ($field in @(
        @{ Value = $Ref; Name = 'Ref' },
        @{ Value = $Workflow; Name = 'Workflow' },
        @{ Value = $RunId; Name = 'RunId' },
        @{ Value = $JavaVersion; Name = 'JavaVersion' },
        @{ Value = $MavenVersion; Name = 'MavenVersion' },
        @{ Value = $NodeVersion; Name = 'NodeVersion' },
        @{ Value = $NpmVersion; Name = 'NpmVersion' }
    )) {
    Assert-NonEmpty -Value $field.Value -Name $field.Name
}

$root = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$jar = Resolve-ExistingFile -Path $JarPath -Name 'Control-plane JAR'
$controlPlaneSbom = Resolve-ExistingFile -Path $ControlPlaneSbomPath -Name 'Control-plane SBOM'
$portalSbom = Resolve-ExistingFile -Path $PortalSbomPath -Name 'Portal SBOM'
if (-not (Test-Path -LiteralPath $PortalDistPath -PathType Container)) {
    throw "Portal distribution is missing: $PortalDistPath"
}

$metadata = [pscustomobject][ordered]@{
    schemaVersion = 1
    commitSha = $CommitSha
    ref = $Ref
    workflow = $Workflow
    runId = $RunId
    sourceDateEpoch = $SourceDateEpoch
    tools = [pscustomobject][ordered]@{
        java = $JavaVersion
        maven = $MavenVersion
        node = $NodeVersion
        npm = $NpmVersion
    }
    artifacts = @(
        New-FileArtifact -Name 'control-plane-jar' -Path $jar -Root $root
        New-PortalTreeArtifact -Path $PortalDistPath -Root $root
        New-FileArtifact -Name 'control-plane-sbom' -Path $controlPlaneSbom -Root $root
        New-FileArtifact -Name 'portal-sbom' -Path $portalSbom -Root $root
    )
}

$outputFullPath = [IO.Path]::GetFullPath($OutputPath)
$outputDirectory = [IO.Path]::GetDirectoryName($outputFullPath)
[IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
$json = $metadata | ConvertTo-Json -Depth 8
[IO.File]::WriteAllText($outputFullPath, $json + "`n", (New-Object Text.UTF8Encoding($false)))
