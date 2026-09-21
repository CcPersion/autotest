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
    SpecPath = 'e2e/project-module.spec.ts'
}
& (Join-Path $PSScriptRoot 'Test-F1-02E2E.ps1') @arguments
exit $LASTEXITCODE
