$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

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

function Test-TextMatch {
    param(
        [string]$Text,
        [string]$Pattern
    )

    return [regex]::IsMatch($Text, $Pattern)
}

$rootPom = Read-Text 'pom.xml'
$apiPom = Read-Text 'platform-api/pom.xml'
$integrationTest = Read-Text 'platform-api/src/test/java/com/autotest/platform/PlatformApiPostgresqlIntegrationTest.java'

Assert-Condition (Test-TextMatch $rootPom '\x3cjackson\.version>2\.18\.3\x3c/jackson\.version>') 'Jackson 版本合同不是 2.18.3。'
Assert-Condition (Test-TextMatch $rootPom '\x3cjunit\.version>5\.11\.4\x3c/junit\.version>') 'JUnit 版本合同不是 5.11.4。'
Assert-Condition (Test-TextMatch $rootPom '\x3ctestcontainers\.version>1\.21\.4\x3c/testcontainers\.version>') 'Testcontainers 版本合同不是 1.21.4。'
Assert-Condition (Test-TextMatch $rootPom '\x3cspring-boot\.version>3\.4\.3\x3c/spring-boot\.version>') 'Spring Boot 版本合同不是 3.4.3。'

$bomOrder = @(
    $rootPom.IndexOf('<artifactId>jackson-bom</artifactId>', [System.StringComparison]::Ordinal),
    $rootPom.IndexOf('<artifactId>junit-bom</artifactId>', [System.StringComparison]::Ordinal),
    $rootPom.IndexOf('<artifactId>testcontainers-bom</artifactId>', [System.StringComparison]::Ordinal),
    $rootPom.IndexOf('<artifactId>spring-boot-dependencies</artifactId>', [System.StringComparison]::Ordinal)
)
Assert-Condition (($bomOrder -notcontains -1) -and (($bomOrder | Sort-Object) -join ',') -eq ($bomOrder -join ',')) '根 POM BOM 顺序必须为 Jackson、JUnit、Testcontainers、Spring Boot。'

Assert-Condition (Test-TextMatch $apiPom '\x3cgroupId>org\.testcontainers\x3c/groupId>\s*\x3cartifactId>junit-jupiter\x3c/artifactId>') 'platform-api 必须使用 Testcontainers 1.x 的 org.testcontainers:junit-jupiter 坐标。'
Assert-Condition (Test-TextMatch $apiPom '\x3cgroupId>org\.testcontainers\x3c/groupId>\s*\x3cartifactId>postgresql\x3c/artifactId>') 'platform-api 必须使用 Testcontainers 1.x 的 org.testcontainers:postgresql 坐标。'
Assert-Condition ((-not (Test-TextMatch $apiPom 'testcontainers-junit-jupiter')) -and (-not (Test-TextMatch $apiPom 'testcontainers-postgresql'))) 'platform-api 仍含 Testcontainers 2.x 专用坐标。'
Assert-Condition (Test-TextMatch $integrationTest 'org\.testcontainers\.containers\.PostgreSQLContainer') '集成测试必须使用 Testcontainers 1.x PostgreSQLContainer 包。'
Assert-Condition (-not (Test-TextMatch $integrationTest 'org\.testcontainers\.postgresql\.PostgreSQLContainer')) '集成测试仍使用 Testcontainers 2.x PostgreSQLContainer 包。'

if ($failures.Count -gt 0) {
    $summary = $failures -join ([Environment]::NewLine + '- ')
    Write-Error ("F1-01 依赖版本合同失败（{0} 项）：{1}{2}- {3}" -f $failures.Count, [Environment]::NewLine, '', $summary)
    exit 1
}

Write-Output 'F1-01 依赖版本合同通过。'
exit 0
