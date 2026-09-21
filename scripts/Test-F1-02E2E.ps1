param(
    [int]$ApiPort = 0,
    [int]$WebPort = 0,
    [int]$DatabasePort = 0,
    [string]$SpecPath = 'e2e/auth.spec.ts',
    [string]$SecretSentinel = ''
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dbName = 'autotest'
$dbUser = 'autotest'
$dbPassword = 'f1_02_e2e_db_password'
$adminUsername = 'admin@example.com'
$adminPassword = 'F1-02-e2e-initial-password'
$changedPassword = 'F1-02-e2e-changed-password'
$masterKey = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('0123456789abcdef0123456789abcdef'))
$containerName = "autotest-f1-02-db-$([Guid]::NewGuid().ToString('N').Substring(0, 10))"
$logDirectory = Join-Path (Join-Path $repo 'output') 'f1-02-e2e'
$apiProcess = $null
$webProcess = $null
$webProcessTreeIds = @()
$apiProcessTreeIds = @()
$previousEnvironment = @{}
$testError = $null
$cleanupErrors = New-Object System.Collections.Generic.List[string]

if ($ApiPort -eq 0) { $ApiPort = Get-Random -Minimum 18080 -Maximum 28080 }
if ($WebPort -eq 0) { $WebPort = Get-Random -Minimum 28080 -Maximum 38080 }
if ($DatabasePort -eq 0) { $DatabasePort = Get-Random -Minimum 38080 -Maximum 48080 }

New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$specFile = Join-Path (Join-Path $repo 'web') $SpecPath
if (-not (Test-Path -LiteralPath $specFile -PathType Leaf)) {
    throw "Playwright spec 不存在：$SpecPath"
}

function Set-ChildEnvironment([hashtable]$values) {
    foreach ($entry in $values.GetEnumerator()) {
        $previousEnvironment[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, 'Process')
    }
}

function Restore-ChildEnvironment {
    foreach ($entry in $previousEnvironment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
    $previousEnvironment.Clear()
}

function Invoke-Wsl([string]$command) {
    $result = & wsl.exe -d Ubuntu -- bash -lc $command 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "WSL 命令失败：$command`n$($result -join "`n")"
    }
    return ($result -join "`n").Trim()
}

function Assert-ContainerAbsent([string]$name) {
    $result = Invoke-Wsl "docker ps -a --filter name=$name --format '{{.Names}}'"
    $matches = @($result -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -eq $name })
    if ($matches.Count -ne 0) {
        throw "容器清理门禁失败：随机容器 $name 仍存在。"
    }
}

function Wait-Http([string]$url, [int]$timeoutSeconds = 90) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    do {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 3
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) { return }
        } catch {
            Start-Sleep -Seconds 1
        }
    } while ((Get-Date) -lt $deadline)
    throw "等待 HTTP 服务超时：$url"
}

function Assert-SecretSentinelAbsent([string]$sentinel) {
    if ([string]::IsNullOrEmpty($sentinel)) { return }
    if ($sentinel -notmatch '^[A-Za-z0-9_-]+$') {
        throw '密钥哨兵只能包含字母、数字、下划线或连字符。'
    }

    $sentinelHex = -join ([Text.Encoding]::UTF8.GetBytes($sentinel) | ForEach-Object { $_.ToString('x2') })
    $query = "SELECT (SELECT count(*) FROM secrets WHERE position('$sentinelHex' in encode(ciphertext, 'hex')) > 0) + (SELECT count(*) FROM environments WHERE position('$sentinel' in variables_json::text) > 0);"
    $queryBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($query))
    $databaseMatches = Invoke-Wsl "echo $queryBase64 | base64 -d | docker exec --interactive $containerName psql --username $dbUser --dbname $dbName --tuples-only --no-align"
    if ($databaseMatches.Trim() -ne '0') {
        throw '密钥哨兵数据库扫描失败：发现明文。'
    }

    $logMatches = @(Get-ChildItem -LiteralPath $logDirectory -File | Select-String -SimpleMatch $sentinel)
    if ($logMatches.Count -ne 0) {
        throw '密钥哨兵日志扫描失败：发现明文。'
    }
    Write-Host '密钥哨兵扫描通过：数据库与服务日志均未发现明文。'
}

function Get-ProcessTreeIds([int]$rootProcessId) {
    $processes = @(Get-CimInstance Win32_Process)
    $ids = New-Object System.Collections.Generic.List[int]
    $pending = New-Object System.Collections.Generic.Queue[int]
    $pending.Enqueue($rootProcessId)
    while ($pending.Count -gt 0) {
        $parentId = $pending.Dequeue()
        if ($ids.Contains($parentId)) { continue }
        $ids.Add($parentId)
        foreach ($child in @($processes | Where-Object { [int]$_.ParentProcessId -eq $parentId })) {
            $pending.Enqueue([int]$child.ProcessId)
        }
    }
    return @($ids)
}

function Stop-ProcessTree([int]$rootProcessId) {
    try {
        & taskkill.exe /PID $rootProcessId /T /F 2>$null | Out-Null
    } catch {
        # The process may have exited between discovery and taskkill.
    }
}

function Get-ListeningPort([int]$port) {
    return @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
}

function Get-E2eWebResidual([int]$port) {
    $portPattern = [regex]::Escape([string]$port)
    return @(Get-CimInstance Win32_Process -Filter "Name = 'node.exe'" | Where-Object {
        $commandLine = [string]$_.CommandLine
        $commandLine -match '--strictPort' -and
            $commandLine -match ("--port(?:=|\s+)" + $portPattern + '(\s|$)')
    })
}

function Get-AliveProcessTreeMembers([int[]]$processIds) {
    if (-not $processIds -or $processIds.Count -eq 0) { return @() }
    return @(Get-CimInstance Win32_Process | Where-Object {
        $processIds -contains [int]$_.ProcessId
    })
}

function Assert-ProcessCleanup([int]$webPort, [int]$apiPort, [int[]]$webTreeIds, [int[]]$apiTreeIds) {
    $deadline = (Get-Date).AddSeconds(10)
    do {
        $webResidual = @(Get-E2eWebResidual $webPort)
        $webListeners = @(Get-ListeningPort $webPort)
        $apiListeners = @(Get-ListeningPort $apiPort)
        $webTreeResidual = @(Get-AliveProcessTreeMembers $webTreeIds)
        $apiTreeResidual = @(Get-AliveProcessTreeMembers $apiTreeIds)
        if ($webResidual.Count -eq 0 -and
            $webListeners.Count -eq 0 -and
            $apiListeners.Count -eq 0 -and
            $webTreeResidual.Count -eq 0 -and
            $apiTreeResidual.Count -eq 0) {
            return
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)

    throw ("清理门禁失败：WebPort {0} 残留 Vite={1}、监听={2}、进程树={3}；ApiPort {4} 监听={5}、Maven进程树={6}。" -f `
        $webPort, $webResidual.Count, $webListeners.Count, $webTreeResidual.Count,
        $apiPort, $apiListeners.Count, $apiTreeResidual.Count)
}

try {
    Write-Host "启动隔离 PostgreSQL 容器 $containerName ..."
    Invoke-Wsl "docker run --detach --name $containerName --publish ${DatabasePort}:5432 --env POSTGRES_DB=$dbName --env POSTGRES_USER=$dbUser --env POSTGRES_PASSWORD=$dbPassword postgres:16-alpine"

    $dbDeadline = (Get-Date).AddSeconds(60)
    do {
        $ready = $false
        try {
            Invoke-Wsl "docker exec $containerName pg_isready --username $dbUser --dbname $dbName" | Out-Null
            $ready = $true
        } catch {
            $ready = $false
        }
        if ($ready) { break }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $dbDeadline)
    if ((Get-Date) -ge $dbDeadline) { throw 'PostgreSQL 容器未在限定时间内就绪。' }

    Write-Host '构建真实 Platform API ...'
    & mvn.cmd -pl platform-api -am -DskipTests install
    if ($LASTEXITCODE -ne 0) { throw 'Platform API 构建失败。' }
    Write-Host '启动真实 Platform API（Spring Boot Maven 插件）...'
    $mvn = (Get-Command mvn.cmd).Source
    Set-ChildEnvironment @{
        SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:$DatabasePort/$dbName"
        SPRING_DATASOURCE_USERNAME = $dbUser
        SPRING_DATASOURCE_PASSWORD = $dbPassword
        AUTOTEST_ADMIN_USERNAME = $adminUsername
        AUTOTEST_ADMIN_PASSWORD = $adminPassword
        AUTOTEST_SESSION_COOKIE_SECURE = 'false'
        AUTOTEST_MASTER_KEY = $masterKey
    }
    $apiProcess = Start-Process -FilePath $mvn -ArgumentList @('-f', 'platform-api/pom.xml', 'org.springframework.boot:spring-boot-maven-plugin:3.4.3:run', '-Dspring-boot.run.main-class=com.autotest.platform.PlatformApiApplication', "-Dspring-boot.run.arguments=--server.port=$ApiPort") -WorkingDirectory $repo -PassThru -RedirectStandardOutput (Join-Path $logDirectory 'platform-api.out.log') -RedirectStandardError (Join-Path $logDirectory 'platform-api.err.log')
    Restore-ChildEnvironment
    Wait-Http "http://127.0.0.1:$ApiPort/actuator/health"

    Set-ChildEnvironment @{ AUTOTEST_PLATFORM_API_URL = "http://127.0.0.1:$ApiPort" }
    $npm = (Get-Command npm.cmd).Source
    $webProcess = Start-Process -FilePath $npm -ArgumentList @('--prefix', 'web', 'run', 'dev', '--', '--host', '127.0.0.1', '--port', $WebPort, '--strictPort') -WorkingDirectory $repo -PassThru -RedirectStandardOutput (Join-Path $logDirectory 'web.out.log') -RedirectStandardError (Join-Path $logDirectory 'web.err.log')
    Restore-ChildEnvironment
    Wait-Http "http://127.0.0.1:$WebPort/"

    Set-ChildEnvironment @{
        E2E_BASE_URL = "http://127.0.0.1:$WebPort"
        E2E_ADMIN_USERNAME = $adminUsername
        E2E_ADMIN_PASSWORD = $adminPassword
        E2E_CHANGED_PASSWORD = $changedPassword
        E2E_SECRET_SENTINEL = $SecretSentinel
    }
    Push-Location $repo
    try {
        $previousErrorAction = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            Write-Host "运行真实浏览器 E2E：$SpecPath"
            & npm.cmd --prefix web exec playwright test $SpecPath -- --workers=1
            $playwrightExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorAction
        }
        if ($playwrightExitCode -ne 0) { throw '真实浏览器 E2E 失败。' }
        Assert-SecretSentinelAbsent $SecretSentinel
    } finally {
        Pop-Location
        Restore-ChildEnvironment
    }
} catch {
    $testError = $_
} finally {
    if ($webProcess) {
        try { $webProcessTreeIds = @(Get-ProcessTreeIds $webProcess.Id) } catch { [void]$cleanupErrors.Add("获取 Web 进程树失败：$($_.Exception.Message)") }
        try { Stop-ProcessTree $webProcess.Id } catch { [void]$cleanupErrors.Add("停止 Web 进程树失败：$($_.Exception.Message)") }
    }
    if ($apiProcess) {
        try { $apiProcessTreeIds = @(Get-ProcessTreeIds $apiProcess.Id) } catch { [void]$cleanupErrors.Add("获取 API 进程树失败：$($_.Exception.Message)") }
        try { Stop-ProcessTree $apiProcess.Id } catch { [void]$cleanupErrors.Add("停止 API 进程树失败：$($_.Exception.Message)") }
    }
    try {
        Assert-ProcessCleanup -webPort $WebPort -apiPort $ApiPort -webTreeIds $webProcessTreeIds -apiTreeIds $apiProcessTreeIds
    } catch {
        [void]$cleanupErrors.Add("进程/端口清理失败：$($_.Exception.Message)")
    }
    try {
        Invoke-Wsl "docker rm --force $containerName" | Out-Null
    } catch {
        [void]$cleanupErrors.Add("删除 PostgreSQL 容器失败：$($_.Exception.Message)")
    }
    try {
        Assert-ContainerAbsent $containerName
    } catch {
        [void]$cleanupErrors.Add("验证 PostgreSQL 容器已删除失败：$($_.Exception.Message)")
    }
}

if ($testError -or $cleanupErrors.Count -gt 0) {
    $failureMessages = New-Object System.Collections.Generic.List[string]
    if ($testError) {
        [void]$failureMessages.Add("原始测试/启动失败：$($testError.Exception.Message)")
    }
    foreach ($cleanupError in $cleanupErrors) {
        [void]$failureMessages.Add("清理失败：$cleanupError")
    }
    throw ($failureMessages -join "`n")
}
