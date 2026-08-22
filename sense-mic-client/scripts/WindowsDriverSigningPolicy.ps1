Set-StrictMode -Version Latest

function Resolve-MicrosoftDriverSigningKind {
    [CmdletBinding()]
    param(
        [AllowEmptyCollection()]
        [string[]]$EnhancedKeyUsageOids = @()
    )

    # Partner Center uses a distinct leaf EKU for attestation.  It must be
    # evaluated before the parent hardware-verification EKU because an
    # attestation certificate is not required to carry both values.
    $attestationOid = '1.3.6.1.4.1.311.10.3.5.1'
    $hardwareVerificationOid = '1.3.6.1.4.1.311.10.3.5'

    if ($EnhancedKeyUsageOids -contains $attestationOid) {
        return 'microsoft-attestation'
    }
    if ($EnhancedKeyUsageOids -contains $hardwareVerificationOid) {
        return 'microsoft-whql-or-hlk'
    }

    throw "Microsoft catalog certificate has neither the attestation EKU ($attestationOid) nor the HLK/WHQL hardware-verification EKU ($hardwareVerificationOid)."
}

function Test-MicrosoftDriverSigningPolicy {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet('microsoft-attestation', 'microsoft-whql-or-hlk')]
        [string]$SigningKind,

        [Parameter(Mandatory = $true)]
        [ValidateSet('Either', 'Attestation', 'WHQL')]
        [string]$Policy
    )

    switch ($Policy) {
        'Either' { return $true }
        'Attestation' { return $SigningKind -eq 'microsoft-attestation' }
        'WHQL' { return $SigningKind -eq 'microsoft-whql-or-hlk' }
    }
}
