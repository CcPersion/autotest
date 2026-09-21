$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$envFile = Join-Path $repoRoot 'deployment/.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw '缺少 deployment/.env；请先复制 deployment/.env.example 并填写本机配置。'
}

$wslRoot = (& wsl.exe wslpath -a ($repoRoot -replace '\\', '/')).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($wslRoot)) {
    throw '无法将仓库路径转换为 WSL 路径。'
}

& wsl.exe -d Ubuntu -- bash -lc "cd '$wslRoot' && exec bash deployment/scripts/start-platform.sh"
if ($LASTEXITCODE -ne 0) {
    throw "WSL 平台启动失败，退出码 $LASTEXITCODE。"
}
