# F4-04 SSRF 目标白名单首片实施计划

> **For agentic workers:** 本计划按 TDD 在当前会话内逐项执行；每个步骤先验证失败，再实现最小代码并复跑定向测试。

**目标：** 为项目运行计划增加显式目标白名单，并让 Platform 与 Runner 在发起 HTTP 请求前拒绝未授权目标。

**架构：** 项目保存域名/IP 白名单；创建运行时把白名单复制进不可变执行计划；Runner 再次校验最终解析出的请求目标。策略只负责 HTTP/HTTPS、主机匹配和明确拒绝，不引入 Redis、租约或新的执行引擎。HTTP 计划统一使用平台审核的 `GuardedHttpSampler`，关闭底层自动重定向并在 JMeter 原生逐跳回调前校验目标。

**技术栈：** Java 17、Spring Boot、Flyway、PostgreSQL、JMeter 5.6.3、JUnit 5、Testcontainers。

**规格依据：** `docs/requirements/01-产品需求规格说明书.md` 第 6.2、6.3、10、13.5 节；`docs/tasks/01-开发任务清单.md` F4-04。

## 全局约束

- 允许内网目标，但必须显式出现在项目白名单。
- URL 只允许 HTTP/HTTPS，不允许用户信息、Fragment、空主机或非规范主机。
- 项目更新继续使用 `revision` CAS；旧运行计划中的白名单不可被后续项目修改影响。
- 不引入多 Runner、Redis、Kafka、Kubernetes、任意脚本或自研 HTTP 执行器。
- 所有说明文档使用中文；旧 `pytest-auto-api` 只读，不复制其源码、密钥和附件。

## 实施步骤

### 任务 1：共享目标匹配策略

**文件：**
- 新建：`shared-contracts/src/main/java/com/autotest/contracts/network/TargetAllowlist.java`
- 新建：`shared-contracts/src/test/java/com/autotest/contracts/TargetAllowlistTest.java`

- [x] 写测试：精确域名、`*.example.test` 子域、显式 IPv4/IPv6 通过；未列出的主机、非 HTTP、用户信息和 Fragment 拒绝；规则空白、重复和超过 100 条拒绝。
- [x] 运行 `mvn -q -pl shared-contracts -Dtest=TargetAllowlistTest test`，确认在类不存在时失败。
- [x] 实现 `TargetAllowlist` 和不可变规则解析；返回 `Decision(allowed, reason, host)`，不输出请求正文或密钥。
- [x] 重跑同一测试并通过。

### 任务 2：项目白名单持久化与 API

**文件：**
- 新建：`platform-api/src/main/resources/db/migration/V15__add_project_target_allowlist.sql`
- 修改：`platform-api/src/main/java/com/autotest/platform/project/ProjectRecord.java`
- 修改：`platform-api/src/main/java/com/autotest/platform/project/ProjectRepository.java`
- 修改：`platform-api/src/main/java/com/autotest/platform/project/ProjectService.java`
- 修改：`platform-api/src/main/java/com/autotest/platform/project/ProjectController.java`
- 测试：扩展现有 `platform-api/src/test/java/com/autotest/platform/project/ProjectModulePostgresqlIntegrationTest.java`

- [x] 在现有真实 PostgreSQL 项目集成测试中覆盖创建/更新白名单、规范化返回和 `revision + 1`；非法规则与旧 revision 冲突已有断言，独立并发 CAS 压测仍未补。
- [x] 运行定向测试，确认迁移/API 字段缺失导致失败。
- [x] 增加 `target_allowlist_json JSONB NOT NULL DEFAULT []` 及数组/长度约束；保留旧 `ProjectRecord` 七参数构造兼容测试夹具。
- [x] 扩展创建/更新请求和响应的可选 `targetAllowlist` 字段，服务层统一解析、去重并限制最多 100 条。
- [x] 重跑定向 PostgreSQL 测试；非法规则和旧 revision 冲突已有项目集成断言，独立并发 CAS 压测仍未补。

### 任务 3：运行计划注入与 Platform 校验

**文件：**
- 修改：`platform-api/src/main/java/com/autotest/platform/run/RunService.java`
- 修改：`platform-api/src/main/java/com/autotest/platform/scenario/ScenarioRunPlanBuilder.java`
- 修改：集合/接口用例构建计划的现有调用链
- 测试：`platform-api/src/test/java/com/autotest/platform/run/TargetAllowlistRunPlanTest.java`

- [x] 先写 Platform policy 单元测试，覆盖白名单注入、原计划不变和绝对 URL 拒绝；运行持久化后的项目变更隔离回归已补。
- [x] 运行定向测试确认当前计划没有策略字段或未拒绝目标。
- [x] 在运行创建入口注入项目白名单，并对 baseUrl 与绝对 URL 做策略校验；SQL/Redis 计划不因无 HTTP 字段被拒绝。
- [x] 重跑定向测试和既有运行计划测试（共享策略、Platform policy、迁移/项目运行集成均通过）。

### 任务 4：Runner 防线与重定向负向证据

**文件：**
- 修改：`runner-app/src/main/java/com/autotest/runner/ExecutionPlanAdapter.java`
- 修改：`runner-app/src/main/java/com/autotest/runner/JmeterPlanCompiler.java`
- 新建：`runner-app/src/test/java/com/autotest/runner/TargetAllowlistRunnerTest.java`

- [x] 先写 Runner 定向测试，覆盖未命中拒绝、显式允许内网目标、缺失策略拒绝和嵌套场景继承。
- [x] 运行测试确认当前 Runner 会放行或无法提供证据。
- [x] 在 `ExecutionPlanAdapter` 重新校验计划中的目标；JMeter 组件层统一使用 `GuardedHttpSampler`，关闭 `auto_redirects` 并在每一跳调用 `HTTPHC4Impl` 前重新校验。
- [x] 重跑 Runner 定向测试；允许→拒绝、允许→允许→拒绝及全允许链均有真实 HTTP 证据，未授权端点请求数为 0。
- [x] 缺失/空白/非法策略在编译期和 Sampler 运行期均 fail-closed，失败样本使用 `TARGET_NOT_ALLOWED` 且 URL 不包含 path/query。
- [x] 修复容器 CLI 的自定义 `saveservice.properties` 和 `TargetAllowlist` 运行时类路径；真实镜像 JMeter CLI 可加载并执行 Guarded JMX。

### 任务 5：文档、清单和门禁

**文件：**
- 修改：`test-fixtures/pytest-auto-api/manifest.json`
- 修改：`docs/reference-acceptance/F4-04-参考能力验收.md`
- 修改：`.superpowers/sdd/2026-09-09-platform-development/progress.md`

- [x] 将 SSRF 用例从 `PENDING` 改为 `COVERED`：真实 HTTP 重定向负向测试证明未访问未授权目标，容器 CLI 回归证明交付镜像可加载自定义组件。
- [x] 已补真实 PostgreSQL 运行计划隔离断言：项目白名单修改后，已保存运行仍保留创建时的规范化白名单与策略标记。
- [x] 运行共享契约、Platform 定向、Runner 定向和根目录 `scripts/verify-local.ps1`。
- [x] 在进度账本中记录迁移版本、测试计数、Luna 实现/测试、Sol 最终复审和剩余边界；不宣称 F4-05 完成。

## 最终门禁记录（2026-09-12）

- Luna 实现：`gpt-5.6-luna` xhigh；Runner 全量 95 项测试通过，0 失败，`mvn -q -pl runner-app -am clean package -DskipTests` 通过。
- 容器证据：`autotest/runner:f4-04-fix1` 使用 JMeter 5.6.3、非 root UID 10001；CLI 生成 1 条 JTL 样本，`responseCode/responseMessage=TARGET_NOT_ALLOWED`，URL 已去除 query。
- Sol 独立最终复审：`gpt-5.6-sol` high，结论 `PASS`；确认自定义 SaveService、四个运行时 class、逐跳校验和 CLI 样本证据均闭合。
- 本任务范围内无未解决阻断；DNS rebinding、完整 Compose 多跳业务流和 F4-05 其他能力仍不属于本任务关闭范围。
