$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$gateScript = Join-Path $PSScriptRoot 'verify-local.ps1'
$failures = [System.Collections.Generic.List[string]]::new()

function Assert-Condition {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        $failures.Add($Message)
    }
}

function Test-SafeTemporaryPath {
    param([string]$Path)

    try {
        $candidate = [System.IO.Path]::GetFullPath($Path).TrimEnd([char[]]@([char]92, [char]47))
        $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd([char[]]@([char]92, [char]47))
        $tempPrefix = $tempRoot + [System.IO.Path]::DirectorySeparatorChar
        return $candidate -ne $tempRoot -and $candidate.StartsWith($tempPrefix, [System.StringComparison]::OrdinalIgnoreCase)
    }
    catch {
        return $false
    }
}

Push-Location $repositoryRoot
try {
    cmd.exe /c exit 7
    try {
        & $gateScript
        $successExitCode = if (Test-Path Variable:LASTEXITCODE) { [int]$LASTEXITCODE } else { 0 }
        Assert-Condition ($successExitCode -eq 0) "旧 LASTEXITCODE 污染探针失败：一键门禁退出码为 $successExitCode。"
    }
    catch {
        Assert-Condition $false "旧 LASTEXITCODE 污染探针异常：$($_.Exception.Message)。"
    }

    $verifyText = Get-Content -Raw -Encoding UTF8 -LiteralPath $gateScript
    Assert-Condition ($verifyText -match 'wsl\.exe\s+-d\s+Ubuntu\s+--\s+bash\s+-lc') '一键门禁未通过 WSL 执行 Maven。'
    Assert-Condition ($verifyText -match 'mvn clean test') '一键门禁的 WSL Maven 命令不完整。'
    Assert-Condition ($verifyText -match '\$global:LASTEXITCODE\s*=\s*0') '一键门禁未在门禁前重置 LASTEXITCODE。'
    Assert-Condition ($verifyText -match '\$commandSucceeded\s*=\s*\$\?') '一键门禁未结合本次命令的 $? 判断结果。'

    & wsl.exe -d Ubuntu -- bash -lc 'exit 17'
    $wslFailureExitCode = $LASTEXITCODE
    Assert-Condition ($wslFailureExitCode -eq 17) "WSL 真实失败命令未传播退出码 17（实际为 $wslFailureExitCode）。"

    $contractScript = Join-Path $PSScriptRoot 'Test-F1-01Contract.ps1'
    Assert-Condition (Test-Path -LiteralPath $contractScript) '缺少 F1-01 依赖版本合同脚本。'
    if (Test-Path -LiteralPath $contractScript) {
        & $contractScript
        Assert-Condition ($LASTEXITCODE -eq 0) 'F1-01 依赖版本合同未通过。'
    }
}
finally {
    Pop-Location
}

if ($failures.Count -gt 0) {
    Write-Error ("F0-03 门禁回归检查失败（{0} 项）：{1}{2}- {3}" -f $failures.Count, [Environment]::NewLine, '', ($failures -join ([Environment]::NewLine + '- ')))
    exit 1
}

Write-Output 'F0-03 门禁回归检查通过。'
