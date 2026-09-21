$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
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

function Read-Text {
    param([string]$RelativePath)

    $path = Join-Path $repositoryRoot $RelativePath
    Assert-Condition (Test-Path -LiteralPath $path) "缺少文件：$RelativePath"
    if (Test-Path -LiteralPath $path) {
        return Get-Content -Raw -Encoding UTF8 -LiteralPath $path
    }
    return ''
}

function Get-JsonPropertyValue {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Object,
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
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

$script:ignoreProbeRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("autotest-f003-ignore-" + [guid]::NewGuid().ToString('N'))
$script:ignoreProbeReady = $false

function Assert-GitIgnoreMatch {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RelativePath,
        [Parameter(Mandatory = $true)]
        [bool]$ShouldBeIgnored
    )

    if (-not $script:ignoreProbeReady) {
        Assert-Condition $false "Git ignore 探针尚未初始化：$RelativePath。"
        return
    }

    & git.exe -C $script:ignoreProbeRoot check-ignore --no-index -q -- $RelativePath 2>$null
    $probeExitCode = $LASTEXITCODE
    $actualIgnored = $probeExitCode -eq 0
    $isExpected = if ($ShouldBeIgnored) { $actualIgnored } else { $probeExitCode -eq 1 }
    $expectation = if ($ShouldBeIgnored) { '应被忽略' } else { '不应被忽略' }
    Assert-Condition $isExpected "Git ignore 探针不符合预期：$RelativePath $expectation（退出码 $probeExitCode）。"
}

$pom = Read-Text 'pom.xml'
Assert-Condition ($pom -match '<java\.version>17</java\.version>') '根 POM 未固定 Java 17。'
foreach ($property in @('spring-boot.version', 'junit.version', 'jackson.version', 'archunit.version')) {
    Assert-Condition ($pom -match "<$([regex]::Escape($property))>[^<]+</") "根 POM 未集中管理版本：$property。"
}

$packageJsonText = Read-Text 'web/package.json'
$packageLockText = Read-Text 'web/package-lock.json'
$packageJson = $packageJsonText | ConvertFrom-Json
foreach ($dependency in @('vue', '@vitejs/plugin-vue', 'typescript', 'vite', 'vitest', 'vue-tsc')) {
    $declaredVersion = Get-JsonPropertyValue $packageJson.dependencies $dependency
    if ($null -eq $declaredVersion) {
        $declaredVersion = Get-JsonPropertyValue $packageJson.devDependencies $dependency
    }
    Assert-Condition ($null -ne $declaredVersion -and $declaredVersion -notmatch '^[\^~]|latest') "前端依赖仍存在漂移版本或缺失：$dependency。"
    $lockPattern = '"' + [regex]::Escape($dependency) + '"\s*:\s*"' + [regex]::Escape($declaredVersion) + '"'
    Assert-Condition ($packageLockText -match $lockPattern) "package.json 与 package-lock.json 版本不一致：$dependency。"
}

$gitignore = Read-Text '.gitignore'
foreach ($entry in @('**/target/', '**/node_modules/', '**/dist/', '.playwright-cli/', '.runtime/', 'runtime/', '.env', '*.pem', '*.key', '*.p12', '*.pfx', '*.jks', '.idea/', '.vscode/')) {
    Assert-Condition ($gitignore -match [regex]::Escape($entry)) ".gitignore 未覆盖：$entry。"
}
Assert-Condition ($gitignore -match '!.env.example') '.gitignore 未允许提交 .env.example。'
foreach ($entry in @('**/data/', 'data/', '**/reports/', '**/runs/')) {
    Assert-Condition ($gitignore -notmatch ('(?m)^' + [regex]::Escape($entry) + '$')) ".gitignore 不得使用宽泛规则：$entry。"
}
try {
    $null = New-Item -ItemType Directory -Path $script:ignoreProbeRoot
    Copy-Item -LiteralPath (Join-Path $repositoryRoot '.gitignore') -Destination (Join-Path $script:ignoreProbeRoot '.gitignore')
    & git.exe -C $script:ignoreProbeRoot init --quiet 2>$null
    if ($LASTEXITCODE -ne 0) {
        Assert-Condition $false 'Git ignore 探针初始化失败。'
    }
    else {
        $script:ignoreProbeReady = $true
        foreach ($path in @(
                'platform-api/src/main/resources/data/seed.json',
                'runner-app/src/test/resources/data/fixture.json',
                'web/src/data/model.ts',
                'docs/data/schema.md',
                'platform-api/src/main/java/com/autotest/reports/ReportService.java',
                'platform-api/src/main/resources/db/migration/V1__init.sql',
                '.env.example'
            )) {
            Assert-GitIgnoreMatch -RelativePath $path -ShouldBeIgnored $false
        }
        foreach ($path in @(
                'runtime/f0-03-run/result.jtl',
                'deployment/runtime/f0-03-run/result.jtl',
                'runtime/f0-03-run/server.key',
                'deployment/runtime/f0-03-run/server.pem',
                '.env',
                'deployment/.env'
            )) {
            Assert-GitIgnoreMatch -RelativePath $path -ShouldBeIgnored $true
        }
    }
}
finally {
    if (Test-Path -LiteralPath $script:ignoreProbeRoot) {
        $resolvedProbeRoot = [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $script:ignoreProbeRoot).Path)
        if (Test-SafeTemporaryPath $resolvedProbeRoot) {
            Remove-Item -LiteralPath $resolvedProbeRoot -Recurse -Force
        }
        else {
            Assert-Condition $false "拒绝删除不安全的 Git ignore 临时目录：$resolvedProbeRoot。"
        }
    }
}

$readme = Read-Text 'README.md'
Assert-Condition ($readme -match 'F0-01') 'README 未说明 F0-01 状态。'
Assert-Condition ($readme -match 'F0-02') 'README 未说明 F0-02 状态。'
Assert-Condition ($readme -match '原型') 'README 未准确说明页面仍是原型。'
Assert-Condition ($readme -match '尚未完成|未完成|尚未形成') 'README 未准确说明平台业务闭环尚未完成。'
Assert-Condition ($readme -notmatch '第一项开发任务会删除 `engine-core`') 'README 仍保留已陈旧的 engine-core 未来时描述。'
Assert-Condition ($readme -match 'mvn clean test') 'README 缺少 Maven 真实命令。'
Assert-Condition ($readme -match 'wsl .*mvn clean test') 'README 的 Maven 单独命令未同步为 WSL 执行。'
Assert-Condition ($readme -match 'npm\.cmd --prefix web') 'README 缺少 Windows 前端真实命令。'
Assert-Condition ($readme -match 'wsl .*docker compose') 'README 缺少 WSL Compose 真实命令。'
Assert-Condition (-not $readme.Contains(('\' + '`'))) 'README 不得使用反斜杠转义行内 Markdown 反引号。'
Assert-Condition (-not $readme.Contains(('\' + '```'))) 'README 不得使用反斜杠转义 Markdown 代码围栏。'

$deploymentReadme = Read-Text 'deployment/README.md'
Assert-Condition ($deploymentReadme -match 'JMeter 5\.6\.3') '部署说明未固定 JMeter 5.6.3 基线。'
Assert-Condition ($deploymentReadme -match '腾讯|镜像') '部署说明未描述国内镜像传输。'
Assert-Condition ($deploymentReadme -match 'SHA-512') '部署说明未描述 Apache 官方 SHA-512 校验。'
Assert-Condition ($deploymentReadme -notmatch '占位|空服务配置') '部署说明仍描述为空 Compose 占位。'

Assert-Condition (Test-Path -LiteralPath (Join-Path $repositoryRoot 'scripts/verify-local.ps1')) '缺少 Windows 一键门禁脚本 scripts/verify-local.ps1。'
Assert-Condition (Test-Path -LiteralPath (Join-Path $repositoryRoot 'scripts/Test-F0-03Gate.ps1')) '缺少一键门禁回归探针 scripts/Test-F0-03Gate.ps1。'
$verifyLocal = Read-Text 'scripts/verify-local.ps1'
Assert-Condition ($verifyLocal -match '\$global:LASTEXITCODE\s*=\s*0') '一键门禁未在每个门禁前重置 LASTEXITCODE。'
Assert-Condition ($verifyLocal -match '\$commandSucceeded\s*=\s*\$\?') '一键门禁未结合本次命令的 $? 判断结果。'
Assert-Condition ($verifyLocal -match '\$wslPathInput\s*=\s*\$repositoryRoot\s+-replace\s+''[^'']+'',\s*''/''') '一键门禁未将 Windows 仓库路径转换为 WSL 斜杠路径。'
Assert-Condition ($verifyLocal -match '\$null\s+-eq\s+\$wslPathOutput') '一键门禁未安全处理 wslpath 空输出。'
$f1Contract = Read-Text 'scripts/Test-F1-01Contract.ps1'
Assert-Condition ($f1Contract -match 'exit\s+0') 'F1-01 合同成功路径未显式返回退出码 0。'

if ($failures.Count -gt 0) {
    $summary = $failures -join ([Environment]::NewLine + '- ')
    Write-Error ("F0-03 合同检查失败（{0} 项）：{1}{2}- {3}" -f $failures.Count, [Environment]::NewLine, '', $summary)
    exit 1
}

Write-Output 'F0-03 合同检查通过。'
