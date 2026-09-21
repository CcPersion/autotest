param(
    [int]$ApiPort = 0,
    [int]$WebPort = 0,
    [int]$DatabasePort = 0
)

$ErrorActionPreference = 'Stop'
$arguments = @{
    ApiPort = $ApiPort
    WebPort = $WebPort
    DatabasePort = $DatabasePort
    SpecPath = 'e2e/environment-secret.spec.ts'
    SecretSentinel = "F104Secret$([Guid]::NewGuid().ToString('N'))"
}
try {
    & (Join-Path $PSScriptRoot 'Test-F1-02E2E.ps1') @arguments
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} catch {
    Write-Error $_
    exit 1
}
