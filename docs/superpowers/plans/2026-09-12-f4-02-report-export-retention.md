# F4-02 报告导出与保留策略实施计划

> **For agentic workers:** 本计划按 TDD 逐项执行；每个步骤先写可失败测试，再实现最小行为。

**目标：** 从现有原生报告生成脱敏 HTML 和 Allure 结果压缩包，并提供项目级运行保留天数与安全清理入口。

**架构：** 导出服务只读取 PostgreSQL 中的原生 `RunReport`，不把 JMeter HTML 作为事实源；HTML 直接返回浏览器下载，Allure 结果由步骤结果生成标准 JSON 后打包返回。保留设置单独存表，清理只删除已结束且超过保留周期的运行及其步骤，不删除执行中的运行。当前切片不引入对象存储客户端，JMX/JTL/日志诊断下载和 MinIO 归档留作后续切片。

**技术栈：** Java 17、Spring Boot、JdbcTemplate、Flyway、Jackson、JUnit 5、Testcontainers PostgreSQL。

**规格依据：** `docs/tasks/01-开发任务清单.md` 的 F4-02；`docs/requirements/01-产品需求规格说明书.md` 第 6.12、6.14、10、11.2 节。

## 全局约束

- 所有导出内容必须复用已入库的脱敏报告，不回显密钥、Authorization、Cookie、令牌或完整敏感响应。
- 更新项目设置使用 `revision` CAS；错误响应沿用统一 API 错误结构。
- 清理只处理 `PASSED`、`FAILED`、`CANCELED`、`INTERRUPTED`、`SKIPPED` 终态，保留 `PENDING`/`RUNNING`。
- 本切片不增加 MinIO SDK、邮件/IM 通道、备份恢复或 JMeter HTML 解析依赖。

---

### 任务 1：报告 HTML 与 Allure 结果导出

**文件：**
- 新建：`platform-api/src/main/java/com/autotest/platform/report/ReportExportService.java`
- 新建：`platform-api/src/main/java/com/autotest/platform/report/ReportExportController.java`
- 新建：`platform-api/src/test/java/com/autotest/platform/report/ReportExportServiceTest.java`

**接口：**
- `ReportExportService.html(UUID projectId, UUID runId)` 返回 UTF-8 HTML 字节。
- `ReportExportService.allureZip(UUID projectId, UUID runId)` 返回包含 `*-result.json` 的 ZIP 字节。
- Controller 提供 `/api/v1/projects/{projectId}/runs/{runId}/exports/html` 和 `/allure`。

- [x] 先写测试：HTML 包含状态和步骤、转义 `<script>`，且不包含明文令牌；Allure ZIP 可读并包含每个步骤结果。
- [x] 运行 `mvn -pl platform-api -Dtest=ReportExportServiceTest test`，确认因类不存在失败。
- [x] 读取 `ReportService.get` 的脱敏 `RunReport`，实现 HTML 转义、Allure 状态映射和 ZIP 写入。
- [x] 接入下载响应的 `Content-Type`、`Content-Disposition` 和报告不存在 404。
- [x] 重新运行定向测试并确认通过。

### 任务 2：项目运行保留设置与清理

**文件：**
- 新建：`platform-api/src/main/resources/db/migration/V13__create_project_retention_settings.sql`
- 新建：`platform-api/src/main/java/com/autotest/platform/retention/RetentionRecord.java`
- 新建：`platform-api/src/main/java/com/autotest/platform/retention/RetentionWrite.java`
- 新建：`platform-api/src/main/java/com/autotest/platform/retention/RetentionRepository.java`
- 新建：`platform-api/src/main/java/com/autotest/platform/retention/RetentionService.java`
- 新建：`platform-api/src/main/java/com/autotest/platform/retention/RetentionController.java`
- 新建：`platform-api/src/test/java/com/autotest/platform/retention/RetentionServiceTest.java`

**接口：**
- `GET /api/v1/projects/{projectId}/retention` 返回 `retentionDays` 与 `revision`。
- `PUT /api/v1/projects/{projectId}/retention` 接收 `{retentionDays, revision}`，范围 1–3650。
- `POST /api/v1/projects/{projectId}/retention/cleanup` 删除已结束且早于截止时间的运行，返回删除数量。

- [x] 先写测试：默认 30 天、范围拒绝、revision 冲突、清理不删除运行中任务且会删除步骤结果。
- [x] 运行定向测试，确认新领域类不存在导致失败。
- [x] 添加 V13 表和 repository/service/controller，使用项目存在校验和 CAS。
- [x] 用事务按“先删步骤、再删运行”执行清理；不处理 JMX/JTL/日志路径文件。
- [x] 重新运行定向测试并确认通过。

### 任务 3：真实 PostgreSQL 与根门禁

**文件：**
- 新建：`platform-api/src/test/java/com/autotest/platform/report/F402ReportRetentionPostgresqlIntegrationTest.java`
- 修改：`platform-api/src/test/java/com/autotest/platform/PlatformApiPostgresqlIntegrationTest.java`（迁移数量与核心表）
- 修改：`.superpowers/sdd/2026-09-09-platform-development/progress.md`

- [x] 先写真实 WSL PostgreSQL 测试：创建运行和步骤，验证 HTML/Allure 导出与脱敏；设置 1 天保留期，验证旧终态删除、活动运行保留。
- [x] 运行该测试，确认迁移/实现缺失时失败。
- [x] 修正迁移与数据库查询，确保二次启动幂等。
- [x] 运行 `scripts/verify-local.ps1`，记录 Maven、Web、类型检查、构建和 Compose config 的新鲜结果。
- [x] 更新进度账本，明确 MinIO、诊断附件和部署级下载仍未完成。

## 当前验证记录

- TDD 红灯已确认：新服务未创建时 `ReportExportServiceTest` 与 `RetentionServiceTest` 分别因符号不存在而失败；实现后定向测试通过（导出 2 项、保留策略 2 项）。
- 真实 WSL PostgreSQL `F402ReportRetentionPostgresqlIntegrationTest` 1 项通过：验证 HTML/Allure 脱敏、Allure ZIP 可读、1 天保留期删除旧终态并保留活动运行。
- 根目录 `scripts/verify-local.ps1` 退出码 0；Maven shared-contracts 16、platform-api 113、runner-app 85；Web Vitest 32 个文件/92 项，前端类型检查、生产构建和 WSL Compose 配置检查通过。

## 交付边界

本计划完成后，平台可以导出脱敏原生 HTML 和 Allure 结果，并按项目策略清理历史数据库报告；不宣称 MinIO 对象归档、JMX/JTL/日志下载或 F4-02 全部部署验收完成。
