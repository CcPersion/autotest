# F1-01 修复第 1 轮限定复审

## 结论

- 复审结论：**PASS**
- 审批结论：**APPROVE**
- 是否允许进入 F1-02：**允许**

本轮只复核上一轮 I-1、I-2、I-3 及其直接回归。三项阻塞均已关闭；未扩查或实现 F1-02。

## 原问题关闭情况

### I-1：有效依赖版本与集中口径不一致——已关闭

根 POM 当前依次导入 Jackson、JUnit、Testcontainers、Spring Boot BOM。重新安装当前父 POM 和 `shared-contracts` 到本地 Maven 仓库后，独立执行 `platform-api` 有效依赖树，结果为：

```text
com.fasterxml.jackson.core:jackson-databind:2.18.3
org.junit.jupiter:junit-jupiter:5.11.4
org.testcontainers:junit-jupiter:1.21.4
org.testcontainers:testcontainers:1.21.4
org.testcontainers:postgresql:1.21.4
org.testcontainers:jdbc:1.21.4
org.testcontainers:database-commons:1.21.4
org.springframework.boot:spring-boot:3.4.3
```

有效版本与根 POM 的唯一版本属性一致，未再出现 Jackson 被 Spring Boot BOM 回退到 2.18.2 的问题。

### I-2：Testcontainers 2.0.5 未证明为最小升级——已关闭

- 根 POM 最终固定 Testcontainers 1.21.4。
- `platform-api/pom.xml` 已恢复 1.x 坐标 `org.testcontainers:junit-jupiter`、`org.testcontainers:postgresql`。
- 集成测试已恢复 `org.testcontainers.containers.PostgreSQLContainer` 包；不存在 2.x 的模块坐标或 PostgreSQLContainer 包名。
- WSL 真实执行 `mvn -pl platform-api -am test` 退出 0。日志明确显示 Testcontainers 1.21.4 连接 Docker Server 29.2.0、API 1.53，启动 PostgreSQL 16.15；首次应用启动执行 Flyway V1，第二次同库启动报告 schema 已为版本 1、无需迁移。

因此已用同一 1.x 主版本内的最小兼容修复替代跨主版本方案。

### I-3：Windows 一键门禁与 WSL Docker 拓扑不匹配——已关闭

`scripts/verify-local.ps1` 现在先用 `wslpath` 解析当前仓库路径，再通过 WSL `bash -lc` 和 `exec mvn clean test` 执行 Maven；前端命令仍在 Windows 执行，Compose 检查仍调用 WSL。当前真实路径 `D:\codexWorkSpec\autotest` 已由完整门禁验证可用。

Windows PowerShell 5.1 执行 `scripts/Test-F0-03Gate.ps1` 退出 0，验证了：

- 前置旧 `LASTEXITCODE=7` 时，完整一键门禁仍成功退出 0；
- WSL Maven 根测试、前端单测、类型检查、生产构建和 WSL Compose config 全部通过；
- 真实 WSL `exit 17` 返回 17；
- 紧随失败探针执行 F1-01 合同时，合同显式 `exit 0`，不受旧退出码污染。

复审另用临时 PowerShell 函数仅在 `verify-local.ps1` 的 WSL Maven 调用处注入真实 `bash -lc 'exit 17'`。一键门禁在“Maven 全量测试”处立即失败，并准确报告“退出码 17”；探针自身退出 0，证明失败码不是只在 WSL 命令外层可见，而是能够穿透实际一键门禁。

## 合同、编码与文档回归

- Windows PowerShell 5.1 运行 `scripts/Test-F1-01Contract.ps1` 退出 0；版本属性、BOM 顺序、1.x 坐标和 Java import 合同均通过。
- `Test-F1-01Contract.ps1`、`verify-local.ps1`、`Test-F0-03Contract.ps1`、`Test-F0-03Gate.ps1` 文件头均为 UTF-8 BOM 字节 `EF BB BF`，中文在 Windows PowerShell 5.1 中正常输出。
- README 已把单独 Maven 命令更新为 WSL 入口，一键门禁说明与当前行为一致。
- F1-01 实施报告和 `progress.md` 均明确最终版本为 1.21.4；2.0.5 只作为已否决的历史尝试保留，并明确“不是最终版本”，没有过时的最终口径。
- 未发现本轮修复引入 F1-02 登录、安全或业务 CRUD 代码。

## 新鲜验证证据

| 验证命令/探针 | 结果 |
| --- | --- |
| `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-F1-01Contract.ps1` | 退出 0，F1-01 依赖合同通过 |
| WSL `mvn -pl platform-api -am test` | 退出 0；共享契约 16/16、Platform API 3/3；Testcontainers 1.21.4 + Docker 29.2.0/API 1.53 + PostgreSQL 16.15 |
| WSL Maven dependency tree | Jackson 2.18.3、JUnit 5.11.4、Testcontainers 1.21.4、Spring Boot 3.4.3 均为有效版本 |
| `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-F0-03Gate.ps1` | 退出 0；WSL 根 Maven 26/26、前端 10/10、typecheck、build、Compose config 全通过 |
| 一键门禁 WSL Maven `exit 17` 注入探针 | 探针退出 0；门禁准确报告 Maven 全量测试退出码 17 |
| 四个 PowerShell 脚本 BOM 字节检查 | 均为 `239,187,191`（UTF-8 BOM） |
| 文档检索 `2.0.5/1.21.4` | 最终口径仅为 1.21.4；2.0.5 仅见于明确标注的历史尝试 |

本轮未修改生产代码、POM、测试或门禁脚本；只新增本复审报告。没有构建镜像，也没有扩查 F1-02。
