param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$scriptPath = (Resolve-Path (Join-Path $PSScriptRoot 'f1-10-wsl-e2e.sh')).Path
if ($scriptPath -notmatch '^(?<drive>[A-Za-z]):\\(?<rest>.*)$') {
    throw "无法转换 WSL 脚本路径：$scriptPath"
}
$scriptWsl = '/mnt/' + $Matches.drive.ToLowerInvariant() + '/' + $Matches.rest.Replace('\', '/')

& wsl.exe -d Ubuntu -- bash $scriptWsl
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
