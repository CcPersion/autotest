# F1-01 独立复审报告

## 结论

- 验收结论：**FAIL**
- 审批结论：**REJECT**
- 是否允许进入 F1-02：**不允许**

核心数据库实现本身已在真实 WSL Docker/PostgreSQL 上跑通：空库由 Flyway 建出 8 张表，同一数据库第二次启动不会重复迁移，健康检查不泄露连接信息，约束负向探针也按预期拒绝非法数据。但当前提交引入了依赖集中管理回归，Testcontainers 2.0.5 未证明是最小兼容升级，而且仓库声明的 Windows 一键门禁在本机标准 Windows+WSL 拓扑中必然失败。这三项在修复前不能批准 F1-01，也不能开始依赖它的 F1-02。

## 必须修复的问题

### I-1：Spring Boot BOM 导入顺序破坏了既有 Jackson 精确版本合同

根 POM 在 `pom.xml:39-63` 依次导入 Testcontainers、Spring Boot、Jackson、JUnit BOM。Maven 实际解析结果为：

```text
com.fasterxml.jackson.core:jackson-databind:2.18.2
org.junit.jupiter:junit-jupiter:5.11.4
```

而根 POM 仍声明 `jackson.version=2.18.3`。也就是说，新增 Spring Boot BOM 后，既有“版本集中且精确”的属性已不再决定实际 Jackson 版本；合同脚本只检查字面属性，未发现有效依赖图已经回退。这是 F1-01 对已通过 F0-03 基线的直接回归。

修复要求：调整 dependencyManagement，使 Jackson、JUnit、Testcontainers 和 Spring Boot 的最终有效版本都与根 POM 的唯一口径一致，并用 `dependency:tree` 或 effective POM 加入可复现验证。不能只保留一个未生效的版本属性。

### I-2：Testcontainers 2.0.5 坐标正确且可运行，但没有证明它是最小升级

当前 2.x 坐标和包名是正确的，实际依赖树也统一解析到 2.0.5：

```text
org.testcontainers:testcontainers-junit-jupiter:2.0.5
org.testcontainers:testcontainers:2.0.5
org.testcontainers:testcontainers-postgresql:2.0.5
org.testcontainers:testcontainers-jdbc:2.0.5
org.testcontainers:testcontainers-database-commons:2.0.5
```

不过实施证据只证明 Spring Boot 管理的 1.20.5 在 Docker 29.2 上失败，随后直接跨到 2.0.5。官方发布记录显示，2.0.0 同时变更了模块坐标和 Java 包名；2.0.2 才把默认 Docker API 提升到 1.44；同一 1.x 主版本的 1.21.4 又明确说明兼容近期 Docker Engine 变更。因此，现有证据不足以证明必须承担 2.x 的坐标/API 迁移，违反“最小实现、不得自行扩大依赖变更”的项目规则。

修复要求二选一：

1. 优先验证并采用仍使用原坐标/包名的最小兼容版本（至少应实测官方为近期 Docker 修复发布的 1.21.4）；或
2. 若 1.21.4 在本项目的 Docker 29.2 环境仍可复现失败，保留完整失败证据，再由 Sol 明确批准 2.x 兼容边界。

参考：[Testcontainers Java 2.0.0、2.0.2 与 1.21.4 官方发布记录](https://github.com/testcontainers/testcontainers-java/releases)、[Docker 29 最低 API 1.44 兼容问题](https://github.com/testcontainers/testcontainers-java/issues/11211)。

### I-3：Windows 一键门禁不能作为“已知环境边界”留到后续

根 README 在 `README.md:41-45` 明确推荐 Windows PowerShell 执行 `scripts\verify-local.ps1`。脚本在 `scripts/verify-local.ps1:36-38` 使用 Windows `mvn.cmd clean test`，而本机 Windows 没有 Docker 命令/上下文；同一台机器的 WSL Ubuntu 则有 Docker 29.2.0、API 1.53。新加入 Testcontainers 集成测试后，一键门禁稳定在 Maven 阶段退出 1，后续前端和 Compose 门禁也不会执行。

这不是可以接受的偶发外部环境限制，而是仓库当前推荐入口与已经确定的 Windows+WSL 运行拓扑不匹配。F0-03 的“一键门禁供后续任务复用”合同已经被 F1-01 改成不可用。

修复要求：保留 PowerShell 一键入口和失败码传播，但让需要 Docker 的 Maven 集成测试通过 WSL 仓库路径执行（或提供等价的 WSL 全量入口并由 PowerShell 包装调用）；不能跳过 Testcontainers，也不能仅在报告中列出手工 WSL 命令。

## 核验通过项

### 数据库结构

`V1__create_core_schema.sql` 与简报冻结的 8 表合同一致：

- 表名完整且仅包含 `users`、`projects`、`modules`、`environments`、`api_definitions`、`api_cases`、`runs`、`step_results`。
- 主键均为 UUID，业务时间为 `timestamptz`；资产表 `revision` 默认 0 且禁止负数；项目、模块、环境、接口定义、接口用例采用 `archived_at`。
- 用户审计外键、项目外键、父模块/接口定义/环境的同项目复合外键以及步骤结果到运行的外键均使用 RESTRICT/默认 NO ACTION，没有级联删除证据。
- 活动项目名、同父模块名、同模块接口名、同接口用例名使用了正确的部分唯一索引，根节点的 NULL 情形另有独立索引。
- JSON 对象/数组、HTTP 方法、运行目标与状态、时间顺序、非负数值、运行幂等键、步骤结果幂等键均有数据库约束。

独立 SQL 负向探针在真实 PostgreSQL 16.15 上确认：跨项目父模块命中 `fk_modules_parent_same_project`；环境变量数组命中 `ck_environments_variables_object`；`TRACE` 命中 `ck_api_definitions_method`；执行计划中的 `token` 键命中 `ck_runs_execution_plan_no_secret_keys`。合法的用户到步骤结果完整链路能够写入，换一个 psql 会话后 8 表数据仍在。探针使用唯一临时容器 `autotest-f101-review-sol`，完成后已删除。

### Flyway、重复启动与健康检查

真实 Testcontainers 测试第一次应用启动执行 `V1`，第二次在同一容器、同一数据库启动时报告 schema 已为版本 1，没有重复迁移；两次均只有 8 张业务表和 1 条成功 Flyway 历史。`/actuator/health` 两次均返回 200/UP。

`application.yml` 只读取 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`，没有默认密码；Actuator 只暴露 health 且 `show-details=never`。集成测试还检查响应不含 `jdbc`、数据库/用户名 `autotest` 和测试密码，WSL 实跑通过。

当前自动化的“业务行数不变”比较的是两次启动前后的空表计数，没有在两次应用启动之间插入非零哨兵数据。它足以证明应用启动没有偷偷写入业务数据，但不能单独证明已有业务行在应用重启后保持。此次人工合法链路探针补充证明了 PostgreSQL 持久化；建议修复轮把一条最小哨兵数据加入集成测试，以免后续回归，但本点不是本次单独拒绝原因。

### 范围控制

启动类仅包含 Spring Boot 入口；未发现 Controller、登录、安全过滤器、Repository、CRUD、角色权限、队列领取、JMeter 调用或复杂快照实现。新增生产依赖限于 Actuator/JDBC/Flyway/PostgreSQL，没有 JPA、Hibernate、H2、Liquibase、Spring Security、Kafka 或 Redis。未越界进入 F1-02。

## 本轮新鲜验证证据

| 验证 | 结果 |
| --- | --- |
| WSL `mvn -pl platform-api -am test` | 通过；共享契约 16/16，Platform API 3/3；Testcontainers 2.0.5 连接 Docker 29.2.0 和真实 PostgreSQL |
| WSL `mvn clean test` | 通过；共享契约 16/16、Platform API 3/3、Runner 7/7，BUILD SUCCESS |
| WSL Testcontainers dependency tree | 2.x 新坐标及全部相关组件统一为 2.0.5 |
| WSL shared-contracts dependency tree | Jackson 实际为 2.18.2，和根属性 2.18.3 不一致；JUnit 为 5.11.4 |
| 真实 PostgreSQL SQL 探针 | 合法 8 表链路通过；4 类 FK/check 负向插入均退出 1 并命中预期约束 |
| Windows `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-local.ps1` | 失败，退出 1；合同检查通过，Maven 到 Testcontainers 时报告找不到有效 Docker 环境 |
| 环境对照 | Windows `Get-Command docker` 退出 1；WSL `docker version` 为 client/server 29.2.0、server API 1.53 |

本轮没有修改生产代码、需求或任务文档，没有构建任何镜像；只复用了本机已有 `postgres:16-alpine` 镜像进行测试，并清理了独立 SQL 探针容器。

## 复审放行条件

完成 I-1、I-2、I-3 后至少重新执行并保存以下新鲜证据：

```powershell
wsl.exe -d Ubuntu -- bash -lc 'cd /mnt/d/codexWorkSpec/autotest && mvn -pl platform-api -am test'
wsl.exe -d Ubuntu -- bash -lc 'cd /mnt/d/codexWorkSpec/autotest && mvn clean test'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-local.ps1
```

同时输出最终 Jackson/JUnit/Testcontainers 依赖树，确认版本属性和有效依赖一致。以上门禁全部通过且复审无新增阻塞后，才允许将 F1-01 标为完成并进入 F1-02。
