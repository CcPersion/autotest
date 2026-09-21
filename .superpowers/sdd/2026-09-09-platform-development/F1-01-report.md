# F1-01 实施报告

## 状态

- 当前状态：实现完成，待Sol复审。
- 本轮范围：完成 F1-01 的 Platform API、PostgreSQL、Flyway、基础表和真实 Testcontainers 集成验证；未实现 F1-02 及后续业务功能。
- 复审边界：本报告记录整改后的新鲜证据，不将门禁通过等同于 Sol 复审完成。

## TDD 证据

先新增真实 PostgreSQL Testcontainers 集成测试，再运行：

```text
wsl.exe -d Ubuntu -- bash -lc 'cd /mnt/d/codexWorkSpec/autotest && mvn -pl platform-api -am -Dtest=PlatformApiPostgresqlIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test'
```

红灯退出码为 1。生产代码和 F1-01 依赖尚未加入时，测试编译明确报告缺少 `spring-jdbc`、Testcontainers 类型以及 `PlatformApiApplication`，证明测试不是空跑。

随后保留以下历史事实，作为整改依据：

- Spring Boot 管理的 Testcontainers 1.20.5 在 WSL Docker 29.2.0 上真实运行时失败：docker-java 请求 API 1.32，而 Docker 服务端最低要求 API 1.44。`-Dapi.version=1.44` 只用于诊断确认环境可运行，未写入最终配置。
- 曾尝试升级到 Testcontainers 2.0.5，并按 2.x 坐标和包名迁移测试；该方案曾在真实 Docker 上运行，但 Sol 复审指出没有证明它是最小兼容升级，要求回到最小变更路径。2.0.5 仅作为历史尝试保留，不是最终版本。

## 实施内容

- 根 `pom.xml` 最终固定 `<testcontainers.version>1.21.4</testcontainers.version>`，依赖管理 BOM 顺序固定为 Jackson、JUnit、Testcontainers、Spring Boot，保留 Java 17 及既有版本集中管理。
- `platform-api/pom.xml` 最终使用 Testcontainers 1.x 坐标 `org.testcontainers:junit-jupiter` 和 `org.testcontainers:postgresql`，不再使用 `testcontainers-junit-jupiter`、`testcontainers-postgresql` 等 2.x 专用坐标；没有加入 JPA/Hibernate、H2、Liquibase、Security、队列或其他 F1-02 生产依赖。
- 新增 `PlatformApiApplication`，仅提供 Spring Boot 启动入口；新增 `application.yml`，只读取 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`，不提供密码默认值。
- 新增 Flyway `V1__create_core_schema.sql`，建立 `users`、`projects`、`modules`、`environments`、`api_definitions`、`api_cases`、`runs`、`step_results` 八张核心表。迁移使用应用生成 UUID、`timestamptz`、非空创建/更新时间、乐观锁初始 `revision=0`、归档时间、RESTRICT 外键、同项目复合外键、活动名称部分唯一索引、JSON 对象/数组约束、运行状态/目标类型约束、结果幂等唯一键和时间/数值边界约束；根模块和根接口分别使用独立的 NULL 部分索引。执行计划拒绝常见密钥字段名，环境变量表不保存密钥字段。
- 新增真实 PostgreSQL 集成测试：同一 `postgres:16-alpine` 容器和数据库连续启动两次，检查 Flyway 只有一次成功迁移、八表齐全、业务行数不变，以及 `/actuator/health` 返回 200/UP 且不泄露 JDBC、用户名、数据库名或密码。
- 集成测试最终使用 `org.testcontainers.containers.PostgreSQLContainer`，不再引用 `org.testcontainers.postgresql.PostgreSQLContainer`。
- `scripts/verify-local.ps1` 的 Maven 门禁改为通过 `wsl.exe -d Ubuntu -- bash -lc` 在 WSL 仓库路径执行 `mvn clean test`；Windows 路径先经 `wslpath` 转换，命令使用 `exec` 保留退出码，并安全处理路径转换失败或空输出。
- `scripts/Test-F1-01Contract.ps1` 成功路径显式 `exit 0`，避免前置 WSL 失败探针留下的 `LASTEXITCODE` 污染合同结果；相关 PowerShell 脚本均保存为 UTF-8 BOM，兼容 Windows PowerShell 5.1。
- `progress.md` 保持 F1-01 状态为“实现完成，待Sol复审”，并追加本次整改与新鲜验证摘要。

## 新鲜验证

| 检查 | 结果 |
| --- | --- |
| Windows PowerShell 5.1 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-F1-01Contract.ps1` | 通过，退出码 0；依赖版本、BOM 顺序、1.x 坐标、包名和集成测试合同全部通过 |
| WSL `mvn -pl platform-api -am test` | 通过，退出码 0；共享契约 16 项、平台 API 3 项全部通过；Testcontainers 1.21.4 连接 Docker 29.2.0（API 1.53）并真实启动 PostgreSQL |
| 主控一键门禁：Windows PowerShell 5.1 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-F0-03Gate.ps1` | 全绿，退出码 0；F0-03 合同、WSL Maven 全量测试、前端单元测试、类型检查、生产构建和 WSL Compose 配置检查全部通过 |
| WSL `mvn clean test`（由 Windows 一键门禁调用） | 通过；共享契约 16 项、平台 API 3 项、Runner 7 项全部通过 |
| 前端门禁（由 Windows 一键门禁调用） | 单元测试 10 项通过，类型检查通过，生产构建成功 |

上述测试均使用真实 Docker 和真实 PostgreSQL，没有使用 H2、Mock 或跳过 Testcontainers。Windows 一键门禁已通过 WSL 包装 Maven，当前已全绿且不存在未处理的环境性阻塞。

## 未验证项与边界

- 尚未实现登录、业务 CRUD、运行队列、JMeter 计划组装、密钥管理和前端业务流程，均留给后续任务。
- 本轮尚未执行 Sol 复审，因此 F1-01 状态仍为“实现完成，待Sol复审”。
- V1 作为已执行迁移不可修改，后续数据库结构变化只能新增 V2 等版本迁移。

最终组件证据：

- [Testcontainers BOM 1.21.4](https://repo1.maven.org/maven2/org/testcontainers/testcontainers-bom/1.21.4/)
- [Testcontainers 1.21.4 POM](https://repo1.maven.org/maven2/org/testcontainers/testcontainers/1.21.4/testcontainers-1.21.4.pom)
