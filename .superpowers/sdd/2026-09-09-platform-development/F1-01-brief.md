# F1-01 实施简报：Platform API、PostgreSQL 与 Flyway

## 目标与边界

让 `platform-api` 成为可启动的 Spring Boot 3.4.3 服务，连接 PostgreSQL，由 Flyway 建立首批 8 张基础表，并只暴露只读健康检查。本任务不实现登录业务、任何 CRUD、管理员初始化、密钥/加密、运行队列领取、JMeter 调用或复杂快照；不修改 `web`、`runner-app`、`shared-contracts`、部署编排和两份权威文档。

完成门禁只有三项：空 PostgreSQL 自动迁移、同一数据库重复启动不重复建表/写业务数据、PostgreSQL Testcontainers 集成测试通过。

## 依赖与启动骨架

- 根 `pom.xml`：导入 `${spring-boot.version}` 对应的 `spring-boot-dependencies` BOM，让 PostgreSQL、Flyway、Actuator 和 Testcontainers 使用 Spring Boot 3.4.3 验证过的版本组合；保留 Java 17 和现有公共版本口径。
- `platform-api/pom.xml`：保留 `spring-boot-starter-web`、ArchUnit；新增 `spring-boot-starter-actuator`、`spring-boot-starter-jdbc`、`flyway-core`、`flyway-database-postgresql`、运行时 `org.postgresql:postgresql`、测试用 `spring-boot-starter-test`、`org.testcontainers:junit-jupiter`、`org.testcontainers:postgresql`。不得引入 JPA、Hibernate、Liquibase、H2、Spring Security、队列或其他生产依赖。
- 新建 `com.autotest.platform.PlatformApiApplication`，只包含 `@SpringBootApplication` 和 `main`；保留现有 `PlatformModule` 与架构门禁。
- 配置只读取 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`；时间约定通过部署 `TZ=UTC` 和 Java/数据库类型约束实现。不得在仓库写默认密码。测试通过 Testcontainers 动态注入连接参数。

## 数据库契约

迁移固定为 `platform-api/src/main/resources/db/migration/V1__create_core_schema.sql`。后续只新增 `V2__<中文含义的英文蛇形名>.sql` 等版本迁移，不修改已经执行的 `V1`，不使用 repeatable migration 保存表结构。

通用约定：主键均为应用生成的 UUID（数据库列 `uuid`，不依赖扩展）；业务时间均为 `timestamptz`，Java 使用 `Instant`，API 输出 UTC 的 ISO-8601 `Z`；`created_at/updated_at` 非空，运行时间可空。可修改资产初始 `revision=0`，更新必须 `WHERE id=? AND revision=?` 并原子加一。归档使用可空 `archived_at`，归档记录默认不出现在活动查询中，恢复时清空；已被引用的记录不物理删除。所有外键采用 `ON DELETE RESTRICT/NO ACTION`，不级联删除业务证据。

| 表 | 第一版关键字段 | 必须落库的约束 |
| --- | --- | --- |
| `users` | `id, username, password_hash, revision, created_at, updated_at` | `lower(username)` 唯一；用户名去首尾空格后非空；`revision >= 0`。不建角色/权限表。 |
| `projects` | `id, name, description, revision, archived_at, created_by, updated_by, created_at, updated_at` | 创建/修改人 FK `users`；活动项目 `lower(name)` 唯一；名称非空。 |
| `modules` | `id, project_id, parent_id, name, sort_order, revision, archived_at, created_by, updated_by, created_at, updated_at` | 项目及用户 FK；用 `(project_id,parent_id)` 复合自 FK 保证父子同项目；同一活动父节点下名称唯一（根节点单独唯一索引）；`sort_order >= 0`，禁止 `id=parent_id`。 |
| `environments` | `id, project_id, name, base_url, variables_json, revision, archived_at, created_by, updated_by, created_at, updated_at` | 项目及用户 FK；活动项目内环境名唯一；`variables_json` 必须为 JSON 对象；名称、`base_url` 非空。本表不保存密钥。 |
| `api_definitions` | `id, project_id, module_id, name, http_method, url_template, request_spec, revision, archived_at, created_by, updated_by, created_at, updated_at` | `(project_id,module_id)` 复合 FK 保证模块同项目；活动模块内名称唯一；方法仅允许 `GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS`；`request_spec` 为 JSON 对象，名称和 URL 模板非空。 |
| `api_cases` | `id, project_id, api_definition_id, name, case_spec, variables_json, assertions_json, revision, archived_at, created_by, updated_by, created_at, updated_at` | `(project_id,api_definition_id)` 复合 FK 保证接口同项目；同一接口下活动用例名唯一；`case_spec/variables_json` 为 JSON 对象，`assertions_json` 为 JSON 数组；名称非空。 |
| `runs` | `id, project_id, environment_id, target_type, target_id, requested_by, status, execution_plan, idempotency_key, jmeter_version, started_at, finished_at, created_at` | 项目/环境/用户 FK且环境同项目；项目内 `idempotency_key` 唯一；状态仅 `PENDING/RUNNING/PASSED/FAILED/CANCELED/INTERRUPTED`；目标类型仅 `API_CASE/SCENARIO/TEST_SUITE`；执行计划为 JSON 对象且不含密钥明文；耗时区间不倒置。该 JSON 是单次运行证据，不是用户管理的版本快照。 |
| `step_results` | `id, run_id, step_id, result_key, sequence_no, status, duration_ms, request_summary, response_summary, assertions_json, error_summary, started_at, finished_at, created_at` | 运行 FK；`(run_id,result_key)` 唯一以支持结果回传幂等；`sequence_no,duration_ms >= 0`；状态仅 `RUNNING/PASSED/FAILED/SKIPPED/CANCELED/INTERRUPTED`；四个结构化字段分别检查为 JSON 对象/数组；时间区间不倒置。 |

为复合 FK 增加必要的 `(project_id,id)` 唯一约束。活动名称唯一使用带 `archived_at IS NULL` 的部分唯一索引；根模块/根接口的 `NULL` 父级分别用独立部分索引处理，不依赖 PostgreSQL 对 `NULL` 的普通唯一语义。

## 健康检查边界

仅暴露 `/actuator/health`：`management.endpoints.web.exposure.include=health`、`management.endpoint.health.show-details=never`。允许 Actuator 用连接校验/`SELECT 1` 判断 PostgreSQL 可达，但健康请求不得创建用户、迁移以外的数据、修改状态、领取任务或泄露 JDBC 地址、用户名、密码和异常栈；不新增自定义业务健康 Controller。Flyway 在应用启动阶段失败即启动失败，不把失败伪装为 `UP`。

## Luna 可执行范围与 TDD

允许修改/新增：

- 根 `pom.xml`、`platform-api/pom.xml`
- `platform-api/src/main/java/com/autotest/platform/PlatformApiApplication.java`
- `platform-api/src/main/resources/application.yml`
- `platform-api/src/main/resources/db/migration/V1__create_core_schema.sql`
- `platform-api/src/test/java/com/autotest/platform/PlatformApiPostgresqlIntegrationTest.java`（如需复用容器，可在同一测试包增加一个最小支持类）
- 完成后写 `.superpowers/sdd/2026-09-09-platform-development/F1-01-report.md`，并更新 `progress.md` 的 F1-01 状态

先写 Testcontainers 红灯测试，再实现最小配置和迁移。测试必须使用真实 PostgreSQL，检查：第一次启动后 Flyway 记录成功且 8 表齐全；关闭并用同一容器/数据库再次启动后仍只有同一迁移、表和业务行数不变；`GET /actuator/health` 返回 200 和 `UP`，且响应不含连接信息。建议新鲜执行：

```powershell
mvn -pl platform-api -am test
mvn clean test
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-local.ps1
```

若 Docker/Testcontainers 不可用，必须准确记录未验证，不能以 H2 或 Mock 替代，也不能宣称 F1-01 完成。
