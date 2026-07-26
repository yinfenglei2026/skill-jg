function Get-GovernanceSyntheticIdentities {
    @(
        [pscustomobject]@{ Username = 'owner.customer.test'; Email = 'owner.customer.test@example.internal'; FirstName = 'Owner'; LastName = 'Customer Test'; Role = 'owner'; Department = 'customer-operations' }
        [pscustomobject]@{ Username = 'reviewer.customer.test'; Email = 'reviewer.customer.test@example.internal'; FirstName = 'Reviewer'; LastName = 'Customer Test'; Role = 'reviewer'; Department = 'customer-operations' }
        [pscustomobject]@{ Username = 'approver.customer.test'; Email = 'approver.customer.test@example.internal'; FirstName = 'Approver'; LastName = 'Customer Test'; Role = 'approver'; Department = 'customer-operations' }
        [pscustomobject]@{ Username = 'operator.customer.test'; Email = 'operator.customer.test@example.internal'; FirstName = 'Operator'; LastName = 'Customer Test'; Role = 'operator'; Department = 'customer-operations' }
        [pscustomobject]@{ Username = 'reader.customer.test'; Email = 'reader.customer.test@example.internal'; FirstName = 'Reader'; LastName = 'Customer Test'; Role = 'read-only'; Department = 'customer-operations' }
        [pscustomobject]@{ Username = 'owner.billing.test'; Email = 'owner.billing.test@example.internal'; FirstName = 'Owner'; LastName = 'Billing Test'; Role = 'owner'; Department = 'billing' }
    )
}

function Get-JwtPayload {
    param(
        [Parameter(Mandatory)]
        [string]$AccessToken
    )

    $segments = $AccessToken.Split('.')
    if ($segments.Count -ne 3) {
        throw 'Access token is not a compact JWT.'
    }

    $value = $segments[1].Replace('-', '+').Replace('_', '/')
    $value += '=' * ((4 - $value.Length % 4) % 4)
    try {
        $json = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($value))
        return $json | ConvertFrom-Json
    } catch {
        throw 'Access token payload is not valid Base64URL JSON.'
    }
}

function Assert-GovernanceJwtClaims {
    param(
        [Parameter(Mandatory)]$Claims,
        [Parameter(Mandatory)][string]$ExpectedDepartment,
        [Parameter(Mandatory)][string]$ExpectedRole
    )

    if ($Claims.department -ne $ExpectedDepartment) {
        throw 'JWT department claim did not match.'
    }
    if ($Claims.realm_access.roles -notcontains $ExpectedRole) {
        throw 'JWT realm role claim did not match.'
    }
}

function ConvertTo-GovernanceProcessArgument {
    param([AllowEmptyString()][string]$Value)

    if ($Value.Length -gt 0 -and $Value -notmatch '[\s"]') {
        return $Value
    }

    $builder = New-Object Text.StringBuilder
    [void]$builder.Append([char]34)
    $backslashes = 0
    foreach ($character in $Value.ToCharArray()) {
        if ($character -eq [char]92) {
            $backslashes++
            continue
        }
        if ($character -eq [char]34) {
            [void]$builder.Append([char]92, ($backslashes * 2) + 1)
            [void]$builder.Append([char]34)
            $backslashes = 0
            continue
        }
        if ($backslashes -gt 0) {
            [void]$builder.Append([char]92, $backslashes)
            $backslashes = 0
        }
        [void]$builder.Append($character)
    }
    if ($backslashes -gt 0) {
        [void]$builder.Append([char]92, $backslashes * 2)
    }
    [void]$builder.Append([char]34)
    return $builder.ToString()
}

function ConvertTo-GovernanceBase64Utf8 {
    param([Parameter(Mandatory)][AllowEmptyString()][string]$Value)

    return [Convert]::ToBase64String([Text.UTF8Encoding]::new($false).GetBytes($Value))
}

function ConvertFrom-GovernanceJsonCollection {
    param([Parameter(Mandatory)][string]$Json)

    $queue = New-Object Collections.Queue
    $queue.Enqueue(($Json | ConvertFrom-Json))
    while ($queue.Count -gt 0) {
        $item = $queue.Dequeue()
        if ($item -is [array]) {
            foreach ($entry in $item) {
                $queue.Enqueue($entry)
            }
        } else {
            Write-Output -NoEnumerate $item
        }
    }
}

function Invoke-GovernanceNativeCommand {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$ArgumentList = @()
    )

    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = $FilePath
    $startInfo.Arguments = (($ArgumentList | ForEach-Object { ConvertTo-GovernanceProcessArgument $_ }) -join ' ')
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $startInfo.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)

    $process = New-Object Diagnostics.Process
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            throw "Native command could not be started: $FilePath"
        }
        $standardOutputTask = $process.StandardOutput.ReadToEndAsync()
        $standardErrorTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $standardOutput = $standardOutputTask.Result
        $standardError = $standardErrorTask.Result
        $exitCode = $process.ExitCode
    } finally {
        $process.Dispose()
    }

    [pscustomobject]@{
        ExitCode = $exitCode
        Output = @($standardOutput)
        ErrorOutput = @($standardError)
    }
}

Export-ModuleMember -Function Get-GovernanceSyntheticIdentities, Get-JwtPayload, Assert-GovernanceJwtClaims, ConvertTo-GovernanceBase64Utf8, ConvertFrom-GovernanceJsonCollection, Invoke-GovernanceNativeCommand
