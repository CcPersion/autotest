param(
    [string]$OutputDirectory = 'backups'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$wslRoot = (& wsl.exe -d Ubuntu -- wslpath -a (($root -replace '\\','/'))) -join "`n"
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($wslRoot)) { throw '无法解析 WSL 仓库路径' }
$outputPath = if ([IO.Path]::IsPathRooted($OutputDirectory)) { $OutputDirectory } else { Join-Path $root $OutputDirectory }
$wslOutput = (& wsl.exe -d Ubuntu -- wslpath -a (($outputPath -replace '\\','/'))) -join "`n"
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($wslOutput)) { throw '无法解析备份目录' }
& wsl.exe -d Ubuntu -- bash -lc "cd '$wslRoot' && bash deployment/scripts/backup-postgres.sh '$wslOutput'"
if ($LASTEXITCODE -ne 0) { throw "PostgreSQL 备份失败，退出码 $LASTEXITCODE" }
