param(
    [string]$SpecPath = 'e2e/f1-10-platform-flow.spec.ts',
    [switch]$ExpectSuiteReport
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$envFile = Join-Path $repoRoot 'deployment/.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw '缺少 deployment/.env；请先复制 deployment/.env.example 并填写配置。'
}
if ([string]::IsNullOrWhiteSpace($env:E2E_ADMIN_PASSWORD)) {
    throw '请通过 E2E_ADMIN_PASSWORD 提供验收管理员密码；脚本不会从日志或仓库读取密码。'
}

$runtimeDir = Join-Path $repoRoot 'deployment/.runtime'
$metaFile = Join-Path $runtimeDir 'f1-10-run-meta.json'
$previousMetaFile = $env:F1_10_RUN_META_FILE
$previousExpectSuiteReport = $env:E2E_EXPECT_SUITE_REPORT
New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
Remove-Item -LiteralPath $metaFile -Force -ErrorAction SilentlyContinue

try {
    & (Join-Path $PSScriptRoot 'start-platform.ps1')
    if ($LASTEXITCODE -ne 0) { throw "平台启动失败，退出码 $LASTEXITCODE。" }

    $env:F1_10_RUN_META_FILE = $metaFile
    $env:E2E_EXPECT_SUITE_REPORT = if ($ExpectSuiteReport) { 'true' } else { 'false' }
    if ([string]::IsNullOrWhiteSpace($env:E2E_BASE_URL)) { $env:E2E_BASE_URL = 'http://127.0.0.1:4173' }
    & npm.cmd --prefix (Join-Path $repoRoot 'web') exec -- playwright test $SpecPath --reporter=line
    if ($LASTEXITCODE -ne 0) { throw "浏览器闭环失败，退出码 $LASTEXITCODE。" }
    if (-not (Test-Path -LiteralPath $metaFile)) { throw '浏览器测试未写入运行编号。' }

    $meta = Get-Content -Raw -LiteralPath $metaFile | ConvertFrom-Json
    if ([string]::IsNullOrWhiteSpace($meta.projectId) -or [string]::IsNullOrWhiteSpace($meta.runId)) {
        throw '运行编号文件缺少 projectId 或 runId。'
    }

    & wsl.exe docker restart deployment-platform-api-1 deployment-web-1 deployment-runner-app-1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '重启 Platform API、Web、Runner 失败。' }

    $healthy = $false
    for ($attempt = 0; $attempt -lt 45; $attempt++) {
        try {
            $apiHealth = (Invoke-RestMethod "$($env:E2E_BASE_URL -replace ':4173', ':8080')/actuator/health").status
            $webHealth = (Invoke-WebRequest -UseBasicParsing $env:E2E_BASE_URL).StatusCode
            $runnerHealth = (wsl.exe docker inspect --format '{{.State.Health.Status}}' deployment-runner-app-1 2>$null).Trim()
            if ($apiHealth -eq 'UP' -and $webHealth -eq 200 -and $runnerHealth -eq 'healthy') { $healthy = $true; break }
        } catch { }
        Start-Sleep -Seconds 1
    }
    if (-not $healthy) { throw '服务重启后未在限定时间内恢复健康。' }

    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $adminUsername = if ([string]::IsNullOrWhiteSpace($env:E2E_ADMIN_USERNAME)) { 'admin@example.com' } else { $env:E2E_ADMIN_USERNAME }
    $loginBody = @{ username = $adminUsername; password = $env:E2E_ADMIN_PASSWORD } | ConvertTo-Json
    Invoke-RestMethod "$($env:E2E_BASE_URL)/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body $loginBody -WebSession $session | Out-Null
    $report = Invoke-RestMethod "$($env:E2E_BASE_URL)/api/v1/projects/$($meta.projectId)/runs/$($meta.runId)/report" -WebSession $session
    if ($report.status -ne 'PASSED' -or @($report.steps).Count -lt 1) {
        throw "重启后报告不符合预期：status=$($report.status)，steps=$(@($report.steps).Count)。"
    }
    if ($ExpectSuiteReport -and @($report.suiteMembers).Count -lt 1) {
        throw '集合报告缺少成员元数据。'
    }
    Write-Host "F1-10 平台闭环通过：runId=$($meta.runId)，重启后报告仍为 PASSED。"
}
finally {
    Remove-Item -LiteralPath $metaFile -Force -ErrorAction SilentlyContinue
    if ($null -eq $previousMetaFile) { Remove-Item Env:F1_10_RUN_META_FILE -ErrorAction SilentlyContinue } else { $env:F1_10_RUN_META_FILE = $previousMetaFile }
    if ($null -eq $previousExpectSuiteReport) { Remove-Item Env:E2E_EXPECT_SUITE_REPORT -ErrorAction SilentlyContinue } else { $env:E2E_EXPECT_SUITE_REPORT = $previousExpectSuiteReport }
}
