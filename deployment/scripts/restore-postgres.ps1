param(
    [Parameter(Mandatory = $true)][string]$BackupFile,
    [switch]$ConfirmRestore
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $ConfirmRestore) { throw '恢复会覆盖当前 PostgreSQL 数据；请显式传入 -ConfirmRestore' }
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$wslRoot = (& wsl.exe -d Ubuntu -- wslpath -a (($root -replace '\\','/'))) -join "`n"
$backupPath = if ([IO.Path]::IsPathRooted($BackupFile)) { $BackupFile } else { Join-Path (Get-Location) $BackupFile }
$wslFile = (& wsl.exe -d Ubuntu -- wslpath -a (($backupPath -replace '\\','/'))) -join "`n"
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($wslRoot) -or [string]::IsNullOrWhiteSpace($wslFile)) { throw '无法解析 WSL 路径' }
& wsl.exe -d Ubuntu -- bash -lc "cd '$wslRoot' && bash deployment/scripts/restore-postgres.sh --confirm '$wslFile'"
if ($LASTEXITCODE -ne 0) { throw "PostgreSQL 恢复失败，退出码 $LASTEXITCODE" }
