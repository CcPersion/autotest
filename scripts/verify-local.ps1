$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repositoryRoot
try {
    $wslPathInput = $repositoryRoot -replace '\\', '/'
    $wslPathOutput = & wsl.exe -d Ubuntu -- wslpath -a $wslPathInput
    $wslPathExitCode = $LASTEXITCODE
    if ($wslPathExitCode -ne 0 -or $null -eq $wslPathOutput) {
        throw "无法解析 WSL 仓库路径：$repositoryRoot"
    }
    $wslRepositoryRoot = ($wslPathOutput -join "`n").Trim()
    if ([string]::IsNullOrWhiteSpace($wslRepositoryRoot)) {
        throw "无法解析 WSL 仓库路径：$repositoryRoot"
    }

    function Invoke-Gate {
        param(
            [Parameter(Mandatory = $true)]
            [string]$Name,
            [Parameter(Mandatory = $true)]
            [scriptblock]$Command
        )

        Write-Host "开始门禁：$Name"
        $global:LASTEXITCODE = 0
        $commandSucceeded = $true
        try {
            & $Command
            $commandSucceeded = $?
        }
        catch {
            $commandSucceeded = $false
            throw
        }
        $exitCode = if (Test-Path Variable:LASTEXITCODE) { [int]$LASTEXITCODE } else { 0 }
        if (-not $commandSucceeded -or $exitCode -ne 0) {
            throw "门禁失败：$Name（退出码 $exitCode）"
        }
        Write-Host "门禁通过：$Name"
    }

    Invoke-Gate 'F0-03 配置合同检查' {
        & (Join-Path $PSScriptRoot 'Test-F0-03Contract.ps1')
    }
    Invoke-Gate 'Maven 全量测试' {
        & wsl.exe -d Ubuntu -- bash -lc "cd '$wslRepositoryRoot' && exec mvn clean test"
    }
    Invoke-Gate '前端单元测试' {
        & npm.cmd --prefix web test -- --run
    }
    Invoke-Gate '前端类型检查' {
        & npm.cmd --prefix web run typecheck
    }
    Invoke-Gate '前端生产构建' {
        & npm.cmd --prefix web run build
    }
    Invoke-Gate 'WSL Docker Compose 配置检查' {
        & wsl.exe -d Ubuntu -- env `
            AUTOTEST_DB_PASSWORD=compose-contract-only `
            AUTOTEST_ADMIN_USERNAME=admin@example.com `
            AUTOTEST_ADMIN_PASSWORD=compose-contract-only `
            AUTOTEST_MASTER_KEY=RjEwNC10ZXN0LW1hc3Rlci1rZXktMzItYnl0ZXMhISE= `
            AUTOTEST_RUNNER_CALLBACK_TOKEN=compose-contract-only `
            docker compose -f deployment/docker-compose.yml config | Out-Null
    }

    Write-Host '本地一键门禁全部通过。'
}
finally {
    Pop-Location
}
