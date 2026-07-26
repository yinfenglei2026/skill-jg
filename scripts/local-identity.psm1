function Get-GovernanceSyntheticIdentities {
    @(
        [pscustomobject]@{ Username = 'owner.customer.test'; Role = 'owner'; Department = 'customer-operations' }
        [pscustomobject]@{ Username = 'reviewer.customer.test'; Role = 'reviewer'; Department = 'customer-operations' }
        [pscustomobject]@{ Username = 'approver.customer.test'; Role = 'approver'; Department = 'customer-operations' }
        [pscustomobject]@{ Username = 'operator.customer.test'; Role = 'operator'; Department = 'customer-operations' }
        [pscustomobject]@{ Username = 'reader.customer.test'; Role = 'read-only'; Department = 'customer-operations' }
        [pscustomobject]@{ Username = 'owner.billing.test'; Role = 'owner'; Department = 'billing' }
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

Export-ModuleMember -Function Get-GovernanceSyntheticIdentities, Get-JwtPayload, Assert-GovernanceJwtClaims
