param(
    [int]$ApiPort = 0,
    [int]$WebPort = 0,
    [string]$SpecPath = 'e2e/f1-09-report-flow.spec.ts'
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$outputRoot = Join-Path (Join-Path $repo 'output') 'f1-09-e2e'
$runId = [Guid]::NewGuid().ToString('N').Substring(0, 10)
$projectName = "autotest-f109-$runId"
$runDirectory = Join-Path $outputRoot $projectName
$composeFile = Join-Path $runDirectory 'compose.yml'
$targetConfig = Join-Path $runDirectory 'target.conf'
$specFile = Join-Path (Join-Path $repo 'web') $SpecPath
$testError = $null
$cleanupErrors = New-Object System.Collections.Generic.List[string]
$previousEnvironment = @{}

$dbName = 'autotest'
$dbUser = 'autotest'
$dbPassword = 'F1-09-e2e-db-password'
$adminUsername = 'admin@example.com'
$adminPassword = 'F1-09-e2e-initial-password'
$callbackToken = 'F1-09-e2e-runner-callback'
$masterKey = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('0123456789abcdef0123456789abcdef'))

if ($ApiPort -eq 0) { $ApiPort = Get-Random -Minimum 18080 -Maximum 28080 }
if ($WebPort -eq 0) { $WebPort = Get-Random -Minimum 28080 -Maximum 38080 }
if (-not (Test-Path -LiteralPath $specFile -PathType Leaf)) {
    throw "Playwright spec 不存在：$SpecPath"
}

New-Item -ItemType Directory -Force -Path $runDirectory | Out-Null

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

function Convert-ToWslPath([string]$path) {
    $full = (Resolve-Path -LiteralPath $path).Path
    if ($full -notmatch '^(?<drive>[A-Za-z]):\\(?<rest>.*)$') {
        throw "只支持 Windows 工作区路径：$full"
    }
    return '/mnt/' + $Matches.drive.ToLowerInvariant() + '/' + $Matches.rest.Replace('\', '/')
}

function Invoke-WslCapture([string]$command, [string]$outputPath, [switch]$ThrowOnFailure) {
    $result = & wsl.exe -d Ubuntu -- bash -lc $command 2>&1
    $result | Out-File -LiteralPath $outputPath -Encoding utf8
    if ($ThrowOnFailure -and $LASTEXITCODE -ne 0) {
        throw "WSL 命令失败（$LASTEXITCODE）：$command`n$($result -join "`n")"
    }
    return [int]$LASTEXITCODE
}

function Assert-ProjectResourcesAbsent {
    $containers = @(Invoke-WslCapture "docker ps -a --filter label=com.docker.compose.project=$projectName --format '{{.Names}}'" (Join-Path $runDirectory 'cleanup-containers-check.log'))
    $volumes = @(Invoke-WslCapture "docker volume ls --filter label=com.docker.compose.project=$projectName --format '{{.Name}}'" (Join-Path $runDirectory 'cleanup-volumes-check.log'))
    $containerLines = @(Get-Content -LiteralPath (Join-Path $runDirectory 'cleanup-containers-check.log') -ErrorAction SilentlyContinue | Where-Object { $_.Trim() })
    $volumeLines = @(Get-Content -LiteralPath (Join-Path $runDirectory 'cleanup-volumes-check.log') -ErrorAction SilentlyContinue | Where-Object { $_.Trim() })
    if ($containerLines.Count -ne 0 -or $volumeLines.Count -ne 0) {
        throw "隔离资源清理门禁失败：容器=$($containerLines -join ',') 卷=$($volumeLines -join ',')"
    }
}

try {
    $repoWsl = Convert-ToWslPath $repo
    $platformContext = (Resolve-Path (Join-Path $repo 'platform-api')).Path
    $runnerContext = (Resolve-Path (Join-Path $repo 'runner-app')).Path
    $webContext = (Resolve-Path (Join-Path $repo 'web')).Path
    $platformContextWsl = Convert-ToWslPath $platformContext
    $runnerContextWsl = Convert-ToWslPath $runnerContext
    $webContextWsl = Convert-ToWslPath $webContext

    $targetConfigText = @"
server {
    listen 8080;
    server_name _;
    default_type application/json;
    location / {
        add_header X-F1-09-Trace "trace-f1-09" always;
        add_header Set-Cookie "sid=f1-09-sensitive-sentinel" always;
        add_header X-Api-Key "f1-09-sensitive-sentinel" always;
        return 200 '{"ok":true,"token":"f1-09-sensitive-sentinel"}';
    }
}
"@
    [IO.File]::WriteAllText($targetConfig, $targetConfigText, [Text.UTF8Encoding]::new($false))
    $targetConfigWsl = Convert-ToWslPath $targetConfig

    $composeText = @"
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: $dbName
      POSTGRES_USER: $dbUser
      POSTGRES_PASSWORD: $dbPassword
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready --username=$dbUser --dbname=$dbName"]
      interval: 3s
      timeout: 3s
      retries: 20

  target:
    image: nginx:1.27-alpine
    networks:
      default:
        ipv4_address: 172.31.90.10
    volumes:
      - ${targetConfigWsl}:/etc/nginx/conf.d/default.conf:ro

  platform-api:
    build:
      context: $platformContextWsl
      dockerfile: Dockerfile
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/$dbName
      SPRING_DATASOURCE_USERNAME: $dbUser
      SPRING_DATASOURCE_PASSWORD: $dbPassword
      AUTOTEST_ADMIN_USERNAME: $adminUsername
      AUTOTEST_ADMIN_PASSWORD: $adminPassword
      AUTOTEST_MASTER_KEY: $masterKey
      AUTOTEST_RUNNER_CALLBACK_TOKEN: $callbackToken
      AUTOTEST_SESSION_COOKIE_SECURE: "false"
      SERVER_PORT: "8080"
    depends_on:
      postgres:
        condition: service_healthy
    ports:
      - "${ApiPort}:8080"
    healthcheck:
      test: ["CMD-SHELL", "wget --spider --quiet http://127.0.0.1:8080/actuator/health"]
      interval: 3s
      timeout: 3s
      retries: 30

  runner-app:
    build:
      context: $runnerContextWsl
      dockerfile: Dockerfile
    environment:
      PLATFORM_DB_URL: jdbc:postgresql://postgres:5432/$dbName
      PLATFORM_DB_USERNAME: $dbUser
      PLATFORM_DB_PASSWORD: $dbPassword
      AUTOTEST_PLATFORM_API_URL: http://platform-api:8080
      AUTOTEST_RUNNER_CALLBACK_TOKEN: $callbackToken
      AUTOTEST_JMETER_VERSION: 5.6.3
      AUTOTEST_RUNNER_POLL_MS: "300"
      JAVA_TOOL_OPTIONS: -Djava.awt.headless=true
    depends_on:
      platform-api:
        condition: service_healthy
    volumes:
      - runner-runs:/work/runs
    command: ["java", "-jar", "/opt/runner/runner.jar"]
    healthcheck:
      test: ["CMD-SHELL", "jmeter --version >/dev/null 2>&1 && test -s /opt/runner/runner.jar"]
      interval: 5s
      timeout: 5s
      retries: 12

  web:
    build:
      context: $webContextWsl
      dockerfile: Dockerfile
    depends_on:
      platform-api:
        condition: service_healthy
    ports:
      - "${WebPort}:80"
    healthcheck:
      test: ["CMD-SHELL", "wget --spider --quiet http://127.0.0.1/"]
      interval: 3s
      timeout: 3s
      retries: 20

volumes:
  postgres-data:
  runner-runs:

networks:
  default:
    ipam:
      config:
        - subnet: 172.31.90.0/24
"@
    [IO.File]::WriteAllText($composeFile, $composeText, [Text.UTF8Encoding]::new($false))

    $composeFileWsl = Convert-ToWslPath $composeFile
    $composePrefix = "docker compose -p $projectName -f $composeFileWsl"
    Invoke-WslCapture "$composePrefix config --quiet" (Join-Path $runDirectory 'compose-config.log') -ThrowOnFailure | Out-Null

    Write-Host "构建隔离 F1-09 Platform/Runner/Web 镜像（Compose project=$projectName）..."
    & mvn.cmd -pl platform-api,runner-app -am -DskipTests package 2>&1 | Tee-Object -FilePath (Join-Path $runDirectory 'maven-package.log')
    if ($LASTEXITCODE -ne 0) { throw 'Platform API/Runner 构建失败。' }

    Write-Host '启动临时 PostgreSQL、Platform API、Runner/JMeter、Web 和目标服务...'
    Invoke-WslCapture "$composePrefix up -d --build --wait --remove-orphans" (Join-Path $runDirectory 'compose-up.log') -ThrowOnFailure | Out-Null

    Set-ChildEnvironment @{
        E2E_BASE_URL = "http://127.0.0.1:$WebPort"
        E2E_ADMIN_USERNAME = $adminUsername
        E2E_ADMIN_PASSWORD = $adminPassword
    }
    Push-Location $repo
    try {
        Write-Host "运行真实 Chromium E2E：$SpecPath"
        & npm.cmd --prefix web exec playwright test $SpecPath -- --workers=1 2>&1 | Tee-Object -FilePath (Join-Path $runDirectory 'playwright.log')
        $playwrightExitCode = $LASTEXITCODE
        if ($playwrightExitCode -ne 0) { throw "真实 Chromium E2E 失败，退出码=$playwrightExitCode。" }
    } finally {
        Pop-Location
        Restore-ChildEnvironment
    }
} catch {
    $testError = $_
} finally {
    if ($previousEnvironment.Count -gt 0) {
        Restore-ChildEnvironment
    }
    if (Test-Path -LiteralPath $composeFile -PathType Leaf) {
        $composePrefix = "docker compose -p $projectName -f $(Convert-ToWslPath $composeFile)"
        Invoke-WslCapture "$composePrefix ps" (Join-Path $runDirectory 'compose-ps-before-cleanup.log') | Out-Null
        Invoke-WslCapture "$composePrefix logs --no-color" (Join-Path $runDirectory 'compose-logs-before-cleanup.log') | Out-Null
        Invoke-WslCapture ('docker run --rm -v ' + $projectName + '_runner-runs:/work/runs:ro nginx:1.27-alpine sh -c ''find /work/runs -type f -name jmeter.log -exec cat {} \;''') (Join-Path $runDirectory 'runner-runtime.log') | Out-Null
        Invoke-WslCapture ('docker run --rm -v ' + $projectName + '_runner-runs:/work/runs:ro nginx:1.27-alpine sh -c ''find /work/runs -maxdepth 3 -type f -exec stat -c "%n %s bytes" {} \;''') (Join-Path $runDirectory 'runner-runs-list.txt') | Out-Null
        $jmxSummaryScript = @'
set -eu
if ! find /work/runs -type f -name plan.jmx -print -quit | grep -q .; then
    printf '%s\n' 'plan.jmx=MISSING'
    exit 0
fi

find /work/runs -type f -name plan.jmx -exec awk '
BEGIN {
    selected[1] = "autotest.target_policy_required"
    selected[2] = "autotest.target_dns_required"
    selected[3] = "autotest.target_allowlist"
    selected[4] = "HTTPSampler.protocol"
    selected[5] = "HTTPSampler.domain"
    selected[6] = "HTTPSampler.port"
    selectedCount = 6
    inProperty = 0
    propertyName = ""
    propertyType = ""
    propertyValue = ""
}

function trim(value) {
    gsub(/^[[:space:]]+/, "", value)
    gsub(/[[:space:]]+$/, "", value)
    return value
}

function xmlUnescape(value) {
    gsub(/&quot;/, "\"", value)
    gsub(/&apos;/, "\047", value)
    gsub(/&lt;/, "<", value)
    gsub(/&gt;/, ">", value)
    gsub(/&amp;/, "\\&", value)
    return value
}

function emit(name, value, rawCount, validCount, i, rule) {
    value = trim(xmlUnescape(value))
    seen[name] = 1
    if (name != "autotest.target_allowlist") {
        print name "=" value
        return
    }

    rawCount = split(value, rawRules, /[[:space:]]+/)
    validCount = 0
    for (i = 1; i <= rawCount; i++) {
        rule = trim(rawRules[i])
        if (rule != "") {
            validCount++
            normalizedRules[validCount] = rule
        }
    }
    if (validCount == 0) {
        print "autotest.target_allowlist.block=EMPTY"
        return
    }

    print "autotest.target_allowlist.block=PRESENT"
    print "autotest.target_allowlist.rule_count=" validCount
    for (i = 1; i <= validCount; i++) {
        print "autotest.target_allowlist.rule[" i "]=" normalizedRules[i]
    }
}

{
    line = $0
    if (inProperty) {
        closeTag = "</" propertyType ">"
        closePosition = index(line, closeTag)
        if (closePosition == 0) {
            propertyValue = propertyValue "\n" line
            next
        }
        propertyValue = propertyValue "\n" substr(line, 1, closePosition - 1)
        emit(propertyName, propertyValue)
        inProperty = 0
        propertyName = ""
        propertyType = ""
        propertyValue = ""
        next
    }

    for (i = 1; i <= selectedCount; i++) {
        candidate = selected[i]
        nameToken = "name=\"" candidate "\""
        namePosition = index(line, nameToken)
        if (namePosition == 0) continue

        prefix = substr(line, 1, namePosition - 1)
        if (prefix ~ /<boolProp[^>]*$/) propertyType = "boolProp"
        else if (prefix ~ /<stringProp[^>]*$/) propertyType = "stringProp"
        else if (prefix ~ /<intProp[^>]*$/) propertyType = "intProp"
        else propertyType = ""
        if (propertyType == "") continue

        propertyName = candidate
        seen[propertyName] = 1
        tagEnd = index(line, ">")
        propertyValue = substr(line, tagEnd + 1)
        closeTag = "</" propertyType ">"
        closePosition = index(propertyValue, closeTag)
        if (closePosition > 0) {
            emit(propertyName, substr(propertyValue, 1, closePosition - 1))
            propertyName = ""
            propertyType = ""
            propertyValue = ""
        } else {
            inProperty = 1
        }
        break
    }
}

END {
    for (i = 1; i <= selectedCount; i++) {
        if (!seen[selected[i]]) {
            if (selected[i] == "autotest.target_allowlist") print "autotest.target_allowlist.block=MISSING"
            else print selected[i] "=MISSING"
        }
    }
}
' {} + 2>/dev/null
'@
        $jmxSummaryPayload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($jmxSummaryScript))
        $jmxSummaryCommand = 'docker run --rm -v ' + $projectName + '_runner-runs:/work/runs:ro nginx:1.27-alpine sh -c "printf %s ' + $jmxSummaryPayload + ' | base64 -d | sh"'
        Invoke-WslCapture $jmxSummaryCommand (Join-Path $runDirectory 'runner-jmx-summary.log') | Out-Null
        $jmxPolicyLinesScript = @'
set -eu
if ! find /work/runs -type f -name plan.jmx -print -quit | grep -q .; then
    printf '%s\n' 'plan.jmx=NOT_FOUND'
    exit 0
fi

find /work/runs -type f -name plan.jmx -exec awk '
BEGIN {
    targetSeen = 0
    allowlistSeen = 0
    samplerSeen = 0
    capture = 0
    captureLines = 0
    closeTag = ""
}

function forbidden(line) {
    return line ~ /HTTPSampler\.path|HeaderManager|HTTPArgument|<body|<Body|name="body"/
}

{
    line = $0
    if (capture) {
        if (forbidden(line) || (line ~ /</ && line !~ closeTag)) {
            print "autotest.target.policy_line=TRUNCATED"
            capture = 0
            next
        }
        print line
        captureLines++
        if (index(line, closeTag) > 0) {
            capture = 0
        } else if (captureLines >= 8) {
            print "autotest.target.policy_line=TRUNCATED"
            capture = 0
        }
        next
    }

    if (forbidden(line)) next
    if (line ~ /GuardedHttpSampler/) {
        print line
        samplerSeen = 1
    }
    if (line !~ /autotest\.target/) next

    print line
    targetSeen = 1
    if (line ~ /name="autotest\.target_allowlist"/) {
        allowlistSeen = 1
        if (line ~ /\/>[[:space:]]*$/ || line ~ />[[:space:]]*<\/(boolProp|stringProp|intProp)>[[:space:]]*$/) {
            print "autotest.target_allowlist=EMPTY_OR_SELF_CLOSING"
        }
    }

    if (line ~ /<(boolProp|stringProp|intProp)[^>]*name="autotest\.target[^"]*"/ \
            && line !~ /\/>[[:space:]]*$/ \
            && line !~ /<\/(boolProp|stringProp|intProp)>[[:space:]]*$/) {
        if (line ~ /<boolProp/) closeTag = "</boolProp>"
        else if (line ~ /<stringProp/) closeTag = "</stringProp>"
        else closeTag = "</intProp>"
        capture = 1
        captureLines = 0
    }
}

END {
    if (!samplerSeen) print "GuardedHttpSampler=NOT_FOUND"
    if (!targetSeen) print "autotest.target=NOT_FOUND"
    if (!allowlistSeen) print "autotest.target_allowlist=NOT_FOUND"
}
' {} + 2>/dev/null
'@
        $jmxPolicyLinesPayload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($jmxPolicyLinesScript))
        $jmxPolicyLinesCommand = 'docker run --rm -v ' + $projectName + '_runner-runs:/work/runs:ro nginx:1.27-alpine sh -c "printf %s ' + $jmxPolicyLinesPayload + ' | base64 -d | sh"'
        Invoke-WslCapture $jmxPolicyLinesCommand (Join-Path $runDirectory 'runner-jmx-policy-lines.log') | Out-Null
        Invoke-WslCapture ('docker run --rm -v ' + $projectName + '_runner-runs:/work/runs:ro nginx:1.27-alpine sh -c ''find /work/runs -type f -name result.jtl -exec grep -o "<httpSample[^>]*" {} \; | sed -E "s/ url=\"[^\"]*\"//g; s/ rm=\"[^\"]*\"//g"''') (Join-Path $runDirectory 'runner-jtl-summary.log') | Out-Null
        $downCode = Invoke-WslCapture "$composePrefix down --volumes --remove-orphans" (Join-Path $runDirectory 'compose-down.log')
        if ($downCode -ne 0) { [void]$cleanupErrors.Add("Compose 清理失败，退出码=$downCode") }
        try {
            Assert-ProjectResourcesAbsent
        } catch {
            [void]$cleanupErrors.Add($_.Exception.Message)
        }
    }
}

if ($testError -or $cleanupErrors.Count -gt 0) {
    $messages = New-Object System.Collections.Generic.List[string]
    if ($testError) { [void]$messages.Add("原始 E2E/启动失败：$($testError.Exception.Message)") }
    foreach ($cleanupError in $cleanupErrors) { [void]$messages.Add("清理失败：$cleanupError") }
    Write-Error ($messages -join "`n")
    Write-Error "失败证据目录：$runDirectory"
    exit 1
}

Write-Host "F1-09 真实 E2E 通过；隔离 Compose project、临时卷和容器已清理。证据目录：$runDirectory"
exit 0
