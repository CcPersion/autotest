# 开发执行记录 — 计划：docs/tasks/01-开发任务清单.md

## 执行边界

- 需求权威：`docs/requirements/01-产品需求规格说明书.md`。
- 任务权威：`docs/tasks/01-开发任务清单.md`。
- 当前仓库不是 Git 仓库，按任务清单第 20 行不初始化 Git；因此无法使用 worktree、提交范围或 Git diff，改用任务前后文件清单、测试输出和独立审查作为证据。
- 编码与 Bug 修复使用 Luna 极高模型；架构和公共契约审查由 Sol 高模型执行。

## 启动核对

| 任务 | 输入/输出关系 | 核对结论 |
| --- | --- | --- |
| F0-01 | 旧 `engine-core` 和旧共享契约 → 精简共享 DTO | 与需求第 3.2 节、任务 F0-01 一致；删除范围明确。 |
| F0-01 → F0-02 | 精简后的 `runner-app` → 固定 JMeter 运行环境 | F0-02 不依赖旧 Java 执行端口，可顺序实施。 |
| F0-02 → F0-03 | Runner 镜像与冒烟计划 → 统一工程门禁 | 输入输出一致，无冲突。 |

## F0-01

- 状态：已完成（2026-09-09）。
- 基线证据：2026-09-09 执行 `mvn test`，旧 `TypedExecutionPortsContractTest.roundTripsTypedExtensionCallSchemas` 失败，35 个共享契约测试中 1 个失败；后续模块因此跳过。
- 验收：删除 `engine-core`；生产代码无 `LeaseDescriptor`、`RunnerWriteEnvelope`、`RootSelector`；必要 DTO JSON 往返；根 `mvn test` 通过。
- 实际结果：删除 `engine-core` 及旧快照、租约、fencing 和纯 Java 执行端口；保留 `ExecutionPlan`、`RunTask`、`RunEvent`、`StepResult`、`AttachmentRef` 五类共享 DTO。
- 修复记录：三轮测试先行修复分别关闭 JsonNode 深层可变/类型丢失、结构化 Authorization/Cookie 明文绕过、`X-Api-Key` 漏检和 `tokenizer`/业务 `headers` 误报。
- 新鲜验证：根目录 `mvn clean test` 通过，共 21 项测试；`engine-core` 不存在；旧四类名称生产引用搜索为零。
- 独立复审：Sol 高模型第 3 轮结论 `PASS / APPROVE`，允许进入 F0-02；完整证据见 `F0-01-rereview-round-3.md`。
- 遗留边界：F0-01 仅验证明确的敏感字段和 Header/Cookie 结构；F1-04 必须根据已知密钥值完成计划组装替换、数据库/日志哨兵扫描和运行时隔离。

## F0-02

- 状态：已完成（2026-09-10）。
- 官方发行证据：Apache 归档中的 `apache-jmeter-5.6.3.tgz.sha512` 为 `5978a1a35edb5a7d428e270564ff49d2b1b257a65e17a759d259a9283fc17093e522fe46f474a043864aea6910683486340706d745fcdf3db1505fd71e689083`。
- 验收：构建非 root、Java 17、固定 JMeter 5.6.3 的 Runner 镜像；容器内版本正确；内置最小 JMX 以非 GUI 模式执行并生成 JTL；不安装第三方插件或桌面图形依赖。
- 实际结果：Runner 镜像固定 JMeter 5.6.3、Java 17、UID 10001、headless 和 `/work/runs`；内置 JMX 仅使用 JMeter 自带组件；Compose 基线可进入 healthy。
- 下载策略：腾讯云镜像负责加速传输，包大小和内容继续用 Apache 官方 SHA-512 校验；有限手工重试支持断点续传，不再从国外慢源盲重试。
- 新鲜验证：JMeter 5.6.3、Java 17.0.20、UID 10001、1 条成功 JTL 样本、Compose config/up healthy 和根 `mvn clean test` 全部通过。
- 独立复审：Sol 高模型结论 `PASS / APPROVE`，允许进入 F0-03；完整证据见 `F0-02-review.md`。
- 遗留边界：当前 healthy 只证明 JMeter CLI 容器基线，不能解释为 Java Runner 队列和任务生命周期已完成。

## F0-03

- 状态：已完成（2026-09-10）。
- 目标：统一工程版本、忽略规则、中文说明和一键本地门禁；不实现业务功能。
- 实际结果：统一 Java/Spring/JUnit/Vue/TypeScript 版本；将 `.gitignore` 收窄到明确运行目录；修复中文 README Markdown；建立合同、失败注入和一键本地门禁脚本。
- 新鲜验证：合同检查、一键门禁及失败传播回归均通过；Maven 25 项、前端 10 项、typecheck、build 与 WSL Compose config 通过。
- 独立复审：Sol 高模型结论 `PASS / APPROVE`，允许进入 F1-01；完整证据见 `F0-03-rereview-round-1.md`。
- 遗留边界：F0 只完成工程与 JMeter 运行基线，尚无登录、业务 API、数据库队列和页面业务闭环。

## F1-01

- 状态：已完成（2026-09-10）。
- 目标：建立可启动的 Platform API、PostgreSQL、Flyway 和首批基础表；以空库迁移、重复启动幂等和 Testcontainers 集成测试作为门禁。
- 整改：最终采用 Testcontainers 1.21.4；根 BOM 顺序为 Jackson、JUnit、Testcontainers、Spring Boot；`platform-api` 使用 1.x 的 `org.testcontainers:junit-jupiter`、`org.testcontainers:postgresql` 坐标及 `org.testcontainers.containers.PostgreSQLContainer` 包。1.20.5 原始 Docker API 红灯和曾尝试 2.0.5 的历史保留在 F1-01 报告中，2.0.5 不作为最终方案。
- 门禁整改：Windows PowerShell 门禁的 Maven 步骤改为 WSL `bash -lc`，先转换 WSL 仓库路径并保留退出码；F1-01 合同成功路径显式返回 0，相关脚本保持 UTF-8 BOM。
- 新鲜验证（2026-09-10）：Windows PowerShell 5.1 执行 `Test-F1-01Contract.ps1` 退出码 0；WSL `mvn -pl platform-api -am test` 退出码 0，Testcontainers 1.21.4 在 Docker 29.2.0 上真实启动 PostgreSQL；Windows PowerShell 5.1 执行 `Test-F0-03Gate.ps1` 退出码 0，Maven、前端单测、类型检查、生产构建和 WSL Compose 检查全部通过。
- 独立复审：Sol 高模型修复第 1 轮结论 `PASS / APPROVE`，允许进入 F1-02；完整证据见 `F1-01-rereview-round-1.md`。

## F1-02

- 状态：完成（2026-09-10），Sol 聚焦复审 `PASS / APPROVE`，允许进入 F1-03。
- 目标：完成单用户服务端 Session 登录闭环、同源 CSRF 前端接入、受保护路由和真实浏览器验收；不扩展 F1-03 资产与执行功能。
- 后端结果：完成 Spring Security、JDBC 用户仓储、管理员初始化、BCrypt、四个认证接口、统一错误与 `traceId`、Session 失效、CSRF 和单实例失败限制；未知用户/错密/锁定均执行 BCrypt 且响应一致，匿名写请求缺 CSRF 返回 401，已登录 CSRF 错误返回 403；限流使用可测试时间源和最近 5 分钟滑动窗口，避免跨边界漏算。
- 前端结果：完成相对 `/api` HTTP 封装、同源凭据、XSRF Cookie 请求头、Session `/me` 恢复、路由守卫、LoginView、PlatformShellView 账户菜单和 Vite `/api` 代理；前端 5 个测试文件共 19 项通过，typecheck/build 退出码 0。
- E2E 结果：Windows PowerShell 5.1 脚本启动隔离 PostgreSQL、真实 Platform API、Vite 和 Chromium；真实浏览器 E2E 1 项通过，覆盖登录失败/成功、刷新恢复、退出、401 和改密生命周期。
- 清理整改：修复 Web 仅停止 `npm.cmd` 父进程导致 Vite 子进程残留的问题，改为按精确根 PID 进程树清理；数据库容器删除失败会聚合为非零退出，并按随机容器名复核不存在。最终 E2E 后 Web/API 端口、进程和容器均无残留。
- 新鲜验证：认证定向测试首轮 4 个类、6 项通过；真实 E2E 1 项通过；随后仅执行一次 `scripts/verify-local.ps1` 总门禁，退出码 0，包含 Maven 32 项、前端 19 项、typecheck/build 和 Compose 配置检查；最后一处滑动窗口修改另有定向测试 3 项通过。
- 依赖范围：新增并精确锁定 Vue Router 4.5.1、Vue Test Utils 2.4.6、jsdom 26.1.0、Playwright Test 1.63.0；后端新增 Spring Security，Testcontainers 保持 1.21.4。
- 独立复审：`F1-02-review.md` 结论 `PASS / APPROVE`，Critical、Important、Minor 均为 0；不把本地 E2E 或门禁解释为生产部署、多实例、F1-03 资产功能或后续 Runner 能力已完成。

## F1-03

- 状态：完成（2026-09-10），Sol 聚焦复审 `PASS / APPROVE`，允许进入 F1-04。
- 目标：实现项目新建/编辑/归档/恢复和项目内模块树新建/改名/排序/移动/删除保护，不扩展 F1-04。
- 后端结果：复用 V1 `projects/modules`，完成 REST、JdbcTemplate 仓储、事务、revision 乐观锁、同项目父节点校验、循环保护、非空删除 details、软删除和跨项目 404。
- 前端结果：顶部真实项目切换和项目管理弹层已接入；接口管理/接口用例共用真实模块树，支持根/子模块、改名、拖拽、删除确认及分类错误提示。
- 问题整改：关闭跨父同名移动 500、真实跨项目 ID 覆盖不足、同父重复排序写入、前端重复提交/请求中再次提交、同父向下拖拽偏一、项目变更后切换器不刷新和祖先文本误定位。
- 新鲜验证：真实 PostgreSQL 定向场景通过；真实浏览器项目/模块 E2E 1 项通过；`scripts/verify-local.ps1` 退出码 0，包含 Maven 34 项、前端 32 项、typecheck/build 和 Compose 配置检查；最终前端修复另有模块树 8 项、typecheck 和 production build 通过。
- 独立复审：`F1-03-review.md` 结论 `PASS / APPROVE`，Critical、Important、Minor 均为 0；接口列表/编辑器仍是原型数据，不宣称 F1-04 之后能力已完成。

## F1-04

- 状态：完成（2026-09-11），Sol 聚焦复审 `PASS / APPROVE`，允许进入 F1-05。
- 目标：完成项目内环境、类型化 JSON 变量和只写不回显密钥管理，不扩展代理、数据源、Runner 注入或接口资产。
- 后端结果：复用 V1 环境表并新增 V2 密钥表；完成环境与密钥 REST、项目隔离、revision 乐观锁、归档语义、严格 `${secret:name}` 引用；使用 AES-256-GCM、随机 nonce 和绑定项目/记录的 AAD 加密，主密钥缺失或非法时失败关闭。
- 前端结果：环境配置页接入真实项目，支持环境新建/编辑/归档/恢复、类型化变量编辑、密钥创建/替换/归档；环境成功变更后同步刷新顶部选择器，密钥明文输入及时清空。
- 安全验收：密钥 API 只返回固定掩码；真实浏览器响应、隔离 PostgreSQL 和 API/Web 日志的随机哨兵明文计数均为 0；E2E 随机容器、端口和进程树清理通过。
- 新鲜验证：后端真实 PostgreSQL 定向测试 2 项、首轮前端定向测试 8 项、真实浏览器 E2E 1 项通过；随后一次 `scripts/verify-local.ps1` 总门禁退出 0，包含 Maven 36 项、前端 47 项、typecheck/build 和 Compose 配置检查；最终错误码文字修正后后端定向测试再次通过。
- 首轮复审整改：环境页切换项目立即清理旧项目数据、表单和密钥明文，并以请求代次隔离乱序列表及全部环境/密钥写响应；浏览器哨兵扫描扩大到全部 `/api/v1/**` 响应。整改后前端定向测试 12 项与 production build 通过，真实 F1-04 E2E 再次通过且数据库/日志/浏览器响应明文仍为 0。
- 详细证据：见 `F1-04-report.md`；当前仍不能宣称 Runner 解密注入、运行时变量替换或 F1-05 接口管理已经完成。
- 独立复审：`F1-04-review.md` 结论 Critical 0、Important 0；唯一非阻断 Minor 为 Runner 注入文案略超前，待真实执行链接入时处理。

## F1-05

- 状态：实现与主控验证完成（2026-09-11），Sol 最终复审待补。Sol 首轮提出的三项 Important 已完成修复；其追加的 OpenAPI 提取器盲区也已修复并由 `OpenApiF105ContractTest` 3/3 定向验证，但当前 Sol 复审额度已耗尽，尚未落最终 PASS。F1-06 已按已批准任务清单开始前端实现，未改变 F1-05 后端合同。
- 目标：保存可执行的 GET/POST + NONE/JSON 接口定义与接口用例结构，不提前实现页面、发送、Runner/JMX 或数据行等后续能力。
- 数据结果：直接复用 V1 `api_definitions/api_cases`，没有修改 V1/V2、没有新增迁移或生产依赖；JSONB 保持参数顺序和类型化变量。
- 后端结果：完成定义/用例 REST、项目/模块/父定义隔离、revision CAS、活动名称唯一、归档语义和字段级校验；支持 Path/Query/Header、用例覆盖、STATUS 及 JSON_PATH EXISTS/EQUALS 断言。
- 文档结果：新增静态 OpenAPI 3.1 JSON 和 Jackson 双向合同测试，文档与 Controller 约定的路径/方法集合一致。
- 新鲜验证：真实 PostgreSQL 集成场景 1 项和 OpenAPI 合同 2 项通过；随后一次 `scripts/verify-local.ps1` 总门禁退出 0，包含 Maven 39 项、前端 51 项、typecheck/build 和 Compose 配置检查。
- 详细证据：见 `F1-05-report.md`；当前不能宣称页面已接入、请求可发送或 JMeter 已能执行这些用例。

## F1-06

- 状态：实现完成（2026-09-11），前端主控与真实浏览器验证通过；Sol 复审待补，不宣称最终验收完成。
- 目标：把接口管理和接口用例页从原型数据接入 F1-05 API，完成保存、刷新、revision 冲突提示；不实现浏览器发送、Runner、JMeter 或场景编排。
- 实际结果：新增接口定义/用例 API 客户端；`ApiStudioView` 接入真实模块树、接口列表、用例列表、GET/POST、Path/Query/Header/JSON Body、变量和断言编辑；POST 自动初始化 JSON Body，GET 自动切换 NONE；页面明确不直连目标服务。
- 新鲜验证：API 客户端与工作台定向测试 3 个文件 5 项通过；前端全量单元测试 17 个文件 56 项通过；typecheck 与 Vite production build 通过；真实 F1-06 浏览器 E2E 1/1 通过，随机 PostgreSQL、API、Vite 和 Chromium 进程清理完成。
- 详细证据：见 `F1-06-report.md`；当前仅需 Sol 复审，不把本轮 E2E 解释为发送、Runner 或 JMeter 已完成。

## F1-07

- 状态：实现完成（2026-09-11），Sol 复审待补；未扩展 F1-08/F1-09。
- 目标：将单接口 GET Query/POST JSON 用例稳定编译为固定 JMeter 5.6.3 可执行的临时 JMX。
- 实际结果：Runner 新增最小单接口 DTO、`JmeterPlanCompiler` 和白名单组件映射；生成 TestPlan、单线程单次迭代、HTTP Request、Header Manager、状态码断言、JSONPath EXISTS/EQUALS 断言和用户变量，稳定写入 stepId；运行时使用官方/等效 5.6.3 SaveService 属性。
- TDD：首轮缺类编译红灯；定向转换测试 2/2 通过；主控最新 `mvn.cmd -pl runner-app -am clean test '-Dsurefire.failIfNoSpecifiedTests=false'` 为 shared-contracts 16 项、runner 9 项全部通过，增量复跑仍为 16+9 全部通过。
- 真实执行：固定 `autotest/runner:0.1.0` 镜像在 WSL Docker 中执行最新 GET/POST JMX 均 exit 0；JTL 分别为 HTTP 200/`success=true`、HTTP 201/`success=true`；随机本地 HTTP 容器已精确清理并复核无残留。
- 详细证据：见 `F1-07-report.md`；当前不宣称队列、JMeter 子进程生命周期、结果回传或报告已完成。

## F1-08

- 状态：Platform API、单 Runner 队列/进程和一次轮询 Worker 已落盘，Sol 复审待补（2026-09-11）。
- 实际结果：复用 `runs` 表完成运行创建、幂等查询、PENDING/RUNNING 取消语义；V3 补充退出码、产物路径和取消标记；Runner 使用 JDBC `FOR UPDATE SKIP LOCKED` 原子领取，启动恢复 RUNNING→INTERRUPTED，JMeter 非零退出不杀 Runner，取消轮询支持优雅停止及强杀兜底。
  - 新增 `ExecutionPlanAdapter` 与 `RunnerWorker.runOnce()`，串起领取、敏感校验、JMX 编译、JMeter 子进程和终态落库；编译失败与取消均有明确终态。
  - 新鲜验证：真实 Testcontainers PostgreSQL 的 Platform API 创建/幂等/查询/取消 1 项通过；WSL Runner 定向 8 项通过（原子领取/恢复、非零进程、计划适配、Worker 成功/取消/编译失败）；Platform API 编译成功。
  - 未完成边界：尚未接入常驻 Runner 容器入口和真实 Platform API→固定 JMeter 镜像部署级闭环；未做 F1-09 JTL 报告、SSE、多 Runner 租约、fencing、Kafka、Kubernetes。
  - 详细证据：见 `F1-08-report.md`。

## F1-09

- 状态：第一条结果采集纵向切片已落盘，仍未完成全部报告验收（2026-09-12）。
- 实际结果：Runner 新增 JTL CSV 解析、步骤 ID/敏感查询参数脱敏和 `JtlResultUploader`；`RunnerWorker` 可在 JMeter 结束后把摘要回传 Platform API。Platform API 复用 `step_results`，提供幂等回传、项目隔离报告查询和入库前统一脱敏。
- 新鲜验证：JTL 定向 2 项通过；Runner 全量 21 项通过（含上传器和 Worker 上传分支）；WSL PostgreSQL Repository 1 项通过；真实 API 回传/重复回传/报告查询 1 项通过；平台全量 21 项通过。
- 未完成边界：Runner 容器启动配置和真实固定 JMeter 镜像→Platform API 网络联调尚未完成，Web 报告页仍为原型数据；未做 SSE、附件、导出、Allure、场景树和 F1-10 全流程。
- 详细证据：见 `F1-09-report.md`。

## F1-10

- 状态：阶段一真实闭环与全新隔离卷验收通过（2026-09-12）；用户环境脚本仍保留为日常启动入口。
- 当前实现：`RunnerConfiguration` 和 `RunnerApplication` 从环境变量读取 PostgreSQL、Platform API 回传地址、回传 Token、JMeter 版本、工作目录和轮询间隔；启动时恢复遗留 `RUNNING` 任务，并以固定轮询领取任务。配置日志不会输出数据库地址、数据库密码、Platform API 地址或回传 Token。Runner Maven 构建生成 fat JAR，Compose 以 `java -jar /opt/runner/runner.jar` 常驻启动。
- 部署实现：Compose 已包含 PostgreSQL、Platform API、Web、Runner 和 `postgres-data`/`runner-runs` 持久化卷；Platform API 使用 Java 17 boot JAR，Web 使用 Nginx 静态镜像，Web 的 Node 依赖在 Linux 镜像构建阶段通过国内 npm 镜像安装，避免 Windows/WSL 共用 `node_modules`。
- 新鲜真实闭环：在独立 Compose 项目 `f1-10-fresh-20260912` 和新建 PostgreSQL/Runner 卷中完成登录、创建项目、环境、GET 接口定义、接口用例；浏览器运行中心提交计划；Runner 领取并调用 JMeter 5.6.3 请求 `http://platform-api:8080/actuator/health`，状态码断言 200，报告返回 1 个 `PASSED` 步骤。Platform API、Web、Runner 重启后健康检查恢复，同一 `runId` 仍可查询 `PASSED` 报告；前端真实 Playwright 流程 `f1-10-platform-flow.spec.ts` 通过。验收后已精确删除该项目容器、网络、卷及临时密钥文件，未触碰既有 `deployment` 项目。
- 新增交付：`deployment/scripts/start-platform.ps1`、`deployment/scripts/verify-platform-flow.ps1`；后者执行浏览器闭环、重启 Platform API/Web/Runner、重新登录并验证同一 `runId` 的报告仍为 `PASSED`，同时清理运行元数据文件。
- 集合验收入口补充：`verify-platform-flow.ps1` 支持 `-SpecPath` 和 `-ExpectSuiteReport`，可在具备 `deployment/.env` 的环境中复用三服务重启/报告恢复门禁检查集合成员元数据；默认 F1-10 参数和行为不变。本机本轮只完成脚本解析与隔离 API/Web/浏览器回归，未读取或写入真实部署密钥。
- 未完成边界：`verify-platform-flow.ps1` 仍要求用户自行准备 `deployment/.env`，本轮使用隔离测试密钥完成等价门禁；场景树、SQL/Redis、SSE、附件、导出、AI 和 F2 完整请求语义仍未完成，不能宣称全需求版本。

## F2-01（首个请求语义切片）

- 状态：进行中（2026-09-12）；仅完成 Runner 与接口编辑器的第一批请求语义，不宣称 F2-01 全部验收。
- Runner 实现：JMeter 计划现在支持 GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS；支持 `NONE`、`JSON`、`TEXT`、`URLENCODED`、`MULTIPART` Body；支持启用/停用 Cookie、CookieManager、跟随重定向、连接/响应超时和 HTTP 代理映射；multipart 文本字段和文件字段进入 JMeter `HTTPFileArg`，路径包含 `..` 时拒绝。
- 安全实现：执行计划中的 Authorization、X-Api-Key、Cookie 等敏感 Header/Cookie 值必须使用 `${secret:name}` 或 Bearer/Basic 密钥模板；普通 Header 不误报。新增 Runner 契约测试覆盖敏感值拒绝、Body 类型、Cookie 和请求选项。
- Platform/Web：接口定义校验和 OpenAPI 合同扩展到七种 HTTP 方法、五种 Body 类型、Cookie、重定向/超时/代理选项；接口编辑页新增 Cookie、Body 类型和高级请求选项；运行中心把定义选项和用例 Cookie 覆盖带入执行计划。
- 新鲜验证：该首轮记录已被后续真实请求与根门禁证据补充，不作为当前最新计数；当前相关门禁已覆盖 Runner 54 项、Platform API 22 项、Web 单测 62 项、typecheck、Vite build 和 WSL Compose config。
- 未完成边界：请求预览的最终浏览器运行证据仍待补齐；F2-01 的全部能力验收仍未关闭。

### F2-01（真实 JMeter 请求验证补充）

- 已补充 `deployment/fixtures/f2-01-http-target.py` 与 `f2-01-http-proxy.py`，分别校验 URL Encoded 字段、Multipart 字段/文件、Cookie、重定向和延迟超时，并提供 HTTP 代理转发夹具。
- 使用全新 WSL 临时网络与 `autotest/runner:0.1.0` 实际执行生成的 JMX：URL Encoded、Multipart、Cookie、重定向、HTTP 代理均返回 `success=true`；超时夹具按预期返回 `SocketTimeoutException` 且 JTL `success=false`。临时容器、网络和 `/tmp/f2-01-http-real` 已清理。
- Run Center 已增加“请求预览”面板，展示最终 URL、Headers、Cookies、Body，敏感字段固定掩码且不直连目标服务。
- PKCS12 首个切片已落地：接口定义高级选项支持 `clientCertificate`（`type=PKCS12`、`secretRef`、`passwordRef`），Platform 保存前校验两项均为已存在的密钥引用；Runner 仅将 Base64 证书密钥解码到运行目录内受限 `.p12` 文件，通过同目录短生命周期属性文件向 JMeter 注入 TLS 参数，JMX、命令行和日志不包含证书密码，运行结束删除属性文件和证书文件。接口编辑器已提供只保存引用的配置入口。
- 新鲜验证：Runner 定向测试通过；新增 keytool 生成临时证书、双向 TLS 本地 HTTPS 服务和真实 JMeter 引擎测试通过，服务端要求客户端证书并返回 200；属性文件传递与清理边界通过；Web typecheck 通过。随后执行根目录 `scripts/verify-local.ps1`，WSL Maven 全量（shared-contracts 16、platform-api 21、runner-app 54）、Web Vitest 61、typecheck、Vite build 和 WSL Compose config 全部通过；门禁退出码 0。Windows 直接运行 Platform Testcontainers 仍受既定 WSL Docker 拓扑限制，正式全量证据以 WSL 门禁为准。
- 环境默认请求配置首个切片已接入：环境新增 `requestOptions` 持久化对象，支持默认 Header、重定向、连接/响应超时、HTTP 代理和 PKCS12 引用；保存时复用接口请求选项校验，敏感 Header/代理密码必须使用密钥引用。环境配置页可编辑 JSON，运行中心将默认 Header 与接口 Header/用例覆盖合并，并将环境级代理、超时、重定向和证书带入执行计划；接口显式配置优先。
- Multipart 文件开关已补齐：Runner 对 `files[].enabled=false` 不再生成 JMeter `HTTPFileArg`；真实 HTTP 夹具现在同时校验普通字段、文件字段名、文件名和文件内容，附带 `deployment/fixtures/report.txt` 可复现验证。
- 本轮定向验证：Runner `JmeterPlanCompilerTest` 13 项通过；WSL 夹具用真实 multipart 请求返回 `200/ok`，错误文件组合返回 `422`，证明服务端校验没有只命中文本字段。
- 环境证书专用表单已接入：环境编辑表单可从当前项目密钥中选择 PKCS12 证书和密码引用，证书页展示当前引用状态；保存仍只提交引用，不接触证书明文。Run Center 请求预览已增加组件回归测试，并把真实浏览器闭环断言加入 F1-10 Playwright 场景。
- 本轮前端验证：EnvironmentView 12 项、RunCenterView 2 项，Web 全量 19 个文件/61 项，typecheck 和生产构建通过；此前旧镜像遗留的浏览器证据已由下述隔离重建 Compose 证据替代。
- 隔离重建 Compose 浏览器验证（2026-09-12）：使用全新 PostgreSQL/Runner 卷重建当前 Platform API、Web、Runner 镜像，真实 Playwright `f1-10-platform-flow.spec.ts` 1/1 通过，覆盖环境默认 Header 保存、请求预览展示、接口/用例创建、Runner 执行和 `PASSED` 报告查看；项目、容器、网络、卷和临时测试密钥已精确清理。
- 本轮发现并修复一个阻断缺陷：接口编辑器默认发送 `requestSpec.options.proxy=null`，Platform 校验器将显式空值误判为代理对象并返回 400，导致页面保存后仍显示 0 条接口。新增 `ApiSpecValidatorTest.acceptsExplicitlyEmptyProxyOptions`，先复现红灯再修复为将 null 视为未配置；定向测试通过，Platform API 当前定向测试计数为 22 项。
- 当前仍未关闭 F2-01：接口级和环境级 PKCS12 引用的完整执行继承、全部请求语义验收矩阵仍需后续证据；本轮已关闭“请求预览最终浏览器运行证据”缺口。

## F2-02（变量、内置函数和运行上下文）

- 状态：已完成（2026-09-19），Sol 最终复审 `PASS`；允许进入 F2-03。
- 实现内容：新增共享 `BuiltinFunctionContract`，Platform 与 Runner 统一校验 `formatdate` 的非空、方括号配对、`DateTimeFormatter` 和 `Locale.ROOT` 语义；函数名严格区分大小写，默认格式为 `yyyy-MM-dd`。
- 运行上下文：固定“提取值 > 数据行 > 接口用例（兼容旧 `variables`）> 场景 > 环境”优先级；完整 JSON 变量保持数字、布尔、对象和数组类型，混合文本按字符串插值；支持 UUID、时间戳、日期、格式化日期、随机整数和随机字符串；普通变量缺失在外部触达前以 `VARIABLE_UNDEFINED` 失败，密钥引用继续走受限 materializer。
- 预检与隔离：Platform 在 `runs.insert` 前校验普通运行和调试运行；测试集合每个启用成员使用自身变量作用域，禁用成员跳过，成员之间不互相借用变量；多数据行逐行校验，任一行缺变量即拒绝入队；Runner 普通集合成员不传播兄弟成员的提取值，成员内部场景仍保持跨步骤提取。
- Luna 新鲜验证：`DateTimePatternChecksTest` 2、`RunServiceTest` 7、`RunPostgresqlIntegrationTest` 2、`ExecutionPlanAdapterTest` 20、`RunVariableContextTest` 8、`RunnerWorkerTest` 25，共 64 项，0 失败、0 错误、0 跳过；全量 WSL `clean test` 返回 0：shared-contracts 26、platform-api 147、runner-app 156，0 失败、0 错误，3 项既有 F107/F108/F404 镜像专项因未配置镜像而跳过。
- Sol 独立最终复审：`gpt-5.6-sol/high`，结论 `PASS`。复核了共享日期格式合同、集合成员隔离、场景成员上下文、逐行预检、`RunService` 接线和文本类型转换。
- 非阻断风险：Platform 与 Runner 的默认日期格式仍有相同字面量重复，未来变更时应统一引用共享常量；集合内“场景成员 + 提取”尚无专门组合测试，但复用的 `executeScenario` 已有跨步骤回归覆盖。

## F2-03（JSONPath 提取器首个切片）

- 状态：进行中（2026-09-12），只交付 JSONPath 首个可执行切片，不宣称完整提取与断言验收。
- 平台契约允许接口用例 `caseSpec.extractors`，每条规则包含 `type`、`expression`、`variable`、可选 `defaultValue` 和 `failIfMissing`；当前白名单类型为 JSONPath、JMESPath、XPath、正则、Header、Cookie。表达式、变量名、未知字段和密钥引用均在保存前校验，OpenAPI 文档同步更新。
- Runner 将规则编译为 JMeter 原生 JSONPostProcessor、JMESPathExtractor、XPath2Extractor 或 RegexExtractor；六种白名单提取器在 `failIfMissing=true` 时追加对应的受控存在性断言，避免提取未命中后继续使用空变量。未使用 JSR223、BeanShell 或任意脚本；完整提取试算和对象/数组类型保持仍待后续切片。
- 页面纵向切片已补齐：接口用例页的“提取器”和“断言”从原始 JSON 文本改为结构化卡片编辑器，支持新增/删除、六种提取器类型、默认值、未命中失败开关，以及状态码、正文、Header/Cookie、JSONPath、JMESPath、XPath、Schema、响应耗时断言和对应操作符；保存时仍生成与后端合同一致的 JSON 结构。
- 当前验证：`ApiDefinitionCasePostgresqlIntegrationTest`（含提取规则正/负向）1 项通过；Runner `ExecutionPlanAdapterTest` 8 项和 `JmeterPlanCompilerTest` 10 项通过；后续门禁计数已更新为 platform-api 22、runner-app 54、Web Vitest 62，并通过 typecheck、Vite build 和 Compose config。真实目标响应的提取结果回传和对象/数组类型保持留待后续切片。
- 本轮 UI 验证：先以结构化编辑器回归测试捕获并修复保存形态，ApiStudioView 定向 3 项、Web 全量 19 个文件/62 项、typecheck 和生产构建通过；重建隔离 WSL Compose 后真实 Playwright F1-10 1/1 通过，实际保存 `$.status → healthStatus` 提取器与 `STATUS=200` 断言，并由 Runner 执行后查看 `PASSED` 报告；隔离资源已精确清理。

### F2-03（响应试算与提取报告证据切片）

- 页面新增“响应试算”面板：输入 JSON/文本响应、状态码、响应头和 Cookie 后，可直接按当前结构化提取规则计算结果；支持 JSONPath、JMESPath、正则、Header、Cookie 和状态码试算，未命中默认值与失败标记可见。
- 试算结果保留 JSON 对象/数组类型，并显示变量名、类型、命中状态和结果；`web/src/utils/extractorTrial.ts` 使用受限路径解析，不直连目标服务。新增工具测试和 ApiStudioView 组件测试，覆盖对象/数组、Header/Cookie、正则和默认值。
- Runner 新增受控 `JmeterExtractionReporter`，不使用 JSR223/BeanShell；在 JMeter PostProcessor 阶段读取白名单提取器变量，将结果以 Base64 元数据写入样本响应头，JTL 解析和上传器恢复为结构化 `extractions`，对象/数组原样保留，敏感变量名在 Runner 和 Platform 两层脱敏。
- Platform `step_results` 新增 `extractions_json`（Flyway V5），报告接口和 Web 报告详情新增“提取”页签；旧回传请求缺少该字段时按空数组兼容。报告入库前按变量名再次屏蔽敏感值。
- TDD/验证：响应试算首轮缺少实现时测试红灯，补齐后工具 2 项、工作台新增场景通过；Runner 真实 JMeter HTTP 测试验证对象/数组元数据、JTL 解码和上传脱敏；Platform Repository、报告脱敏和真实 PostgreSQL 报告回传/查询回归通过。当前仍需执行根门禁和隔离 Compose 重建后的真实报告提取页浏览器验证，F2-03 仍未整体关闭。
- 根门禁补充验证（2026-09-12）：WSL Maven 全量 shared-contracts 16、platform-api 23、runner-app 56 全部通过；Web Vitest 20 个文件/65 项、typecheck、生产构建和 Compose 配置全部通过。根门禁退出码 0。真实隔离 Compose 的提取报告页浏览器验证仍待补，不能据此宣称 F2-03 全部关闭。

### F2-03（响应断言编译补充）

- 已补齐平台与 Runner 的断言类型合同：状态码、JSONPath、JMESPath、XPath、响应正文、响应 Header、Cookie、JSON Schema 和响应耗时；结构化断言支持 EXISTS/NOT_EXISTS/EQUALS/NOT_EQUALS/CONTAINS/NOT_CONTAINS，正文/Header/Cookie 支持 EQUALS/CONTAINS/NOT_CONTAINS/MATCHES，JSON Schema 支持受控子集 VALIDATE，响应耗时支持 LESS_THAN。
- Runner 统一通过 JMeter 原生 `ResponseAssertion`、`JSONPathAssertion`、`JMESPathAssertion`、`XPath2Assertion`、受控 `JmeterJsonSchemaAssertion` 和 `DurationAssertion` 编译，禁止脚本型断言；Header/Cookie 提取器使用受控 Java 存在性断言，六种提取器的 `failIfMissing` 语义均已接入。
- 新鲜验证：Runner 定向 `JmeterPlanCompilerTest` 10 项、`JmeterHeaderCookiePresenceAssertionTest` 2 项和 `JtlResultUploaderTest` 2 项通过；真实本地 HTTP JMeter 引擎已覆盖状态码、JSONPath、JMESPath、正则、Header、Cookie、正文、JSON Schema 和耗时断言，提取器 `failIfMissing` 对六类白名单类型均生成受控失败断言；失败回传按换行拆分为多个报告断言项。真实 PostgreSQL `ApiDefinitionCasePostgresqlIntegrationTest` 已保存并读回全部断言类型（1 项通过）；OpenAPI 合同 3 项通过；当前根门禁为 Maven 16+21+54、Web Vitest 61、typecheck/build 和 Compose config 全部通过。完整提取试算 UI、对象/数组提取类型保持和报告呈现仍待后续任务，不能宣称 F2-03 全部完成。

### F2-03 当前首片门禁复核（2026-09-21）

- 状态：进行中；本轮完成“提取/断言共享求值、试算 API、JMeter 真实元数据和报告事实脱敏”的首片整改，未将 F2-03 整体标记为已完成。
- 实际修改：共享受控提取/断言求值补齐 JMESPath 受限字面量比较和 JSON 类型/默认值语义；Runner 增加 `JmeterControlledAssertion`、`JmeterExtractionReporter` 三态默认值编码、规则索引和结构化 JTL 提取/断言事实，补齐 VARIABLE、数值比较、Header/Cookie 操作及 Cookie `Set-Cookie` 精确取值；Platform 接入提取试算 API，并在 `ReportSanitizer` 入库边界保留 JSON null、屏蔽密钥函数表达式；Web 编辑器接入 VARIABLE、数值/响应操作符和 Header/Cookie 表达式合同。
- Luna 新鲜验证：`mvn.cmd -q -pl shared-contracts -am test` 39 项通过；Runner 定向 `JmeterAssertionTest` 5、`JmeterPlanCompilerTest` 23、`JtlResultUploaderTest` 14、`JtlParserTest` 7、`ExecutionPlanAdapterTest` 21，共 70 项通过；Platform 定向 `ExtractorTrialServiceTest` 6、`ExtractorTrialControllerTest` 2、`ApiSpecValidatorTest` 10、`ReportSanitizerTest` 8，共 26 项通过；Web 上轮提取试算/API Studio 14 项、typecheck 和生产构建通过。真实 JMeter 测试包含默认值 absent/null/empty/string 三态和多断言执行。
- Sol 独立门禁：首轮审查 `gpt-5.6-sol/high` 发现 4 类阻断并交回 Luna；二轮审查确认前三类整改，唯一剩余的 Platform 密钥函数脱敏问题已修复；最终复审 `gpt-5.6-sol/high` 结论 `PASS`。
- 未关闭风险：尚未执行完整 CLI→XML JTL→Runner→Platform 的“双断言同时失败”真实链路、动态试算 no-network/no-db 探针及本轮 Web 重跑；这些是后续 F2-03 整体验收证据，不把本轮首片 PASS 等同于任务整体完成。

## F2-04（数据行编辑与结构校验首个切片）

- 状态：进行中（2026-09-12）；已完成数据行资产编辑、CSV 交换和保存前结构校验，尚未宣称多数据行实际执行、逐行变量作用域或报告分组完成。
- 前端结果：接口用例页新增“数据行”标签，支持增加/删除行、增加列、启用开关、CSV 导入/导出和“单行失败后是否继续”；CSV 解析保留逗号、换行、中文和空值，按首见列顺序导出。保存时数据行和选项写入现有 `caseSpec` JSON，不新增表和迁移。
- 后端结果：`caseSpec` 接受 `dataRows` 和 `dataRowOptions`；校验数据行 id 唯一、启用字段类型、values 变量名、标量值类型和未知字段，支持引用已登记的密钥/变量模板。运行中心已把数据行及继续策略带入执行计划。Runner 已按启用行逐行编译/执行，行变量覆盖用例变量；结果键附加行 ID，避免多行结果幂等冲突；失败后继续时保留整体失败状态。
- TDD/定向验证：CSV 工具 2 项、ApiStudioView/RunCenterView 定向场景通过；`ApiSpecValidatorTest` 先因未知 `dataRows` 字段红灯，再补齐校验后 3 项通过；Runner 数据行解析/变量优先级和 Worker 多行执行回归通过。最新 WSL 根门禁：shared-contracts 16、platform-api 25、runner-app 59，Web Vitest 21 个文件/68 项，typecheck、Vite build、Compose config 全部通过并退出 0。真实 Compose 多行目标与报告页面分组验收仍待下一步。

### F2-04 收口（2026-09-12）

- 真实隔离 Compose 使用全新 PostgreSQL/Runner 卷和重建后的 Web/API/Runner 镜像，Playwright `f2-04-data-rows.spec.ts` 1/1 通过：三条数据行按顺序执行，URL 中分别出现 `row=first/second/third`，报告树产生 3 个独立节点，结果键互不相同；容器、卷、网络已精确清理。
- 报告详情新增数据行标签解析（从逐行结果键提取行 ID，并在步骤树和证据头部展示短标签）；新增工具单元测试 2 项。Web 全量回归更新为 22 个文件/71 项，typecheck 和生产构建通过。
- 结论：F2-04 的数据表、CSV 往返、结构校验、逐行变量隔离、继续策略和独立报告节点均已有证据；后续仅需在 F2-09 汇总失败/清理语义时补充跨场景组合验证。

## F2-05（场景模型和树形编排器首个切片）

- 状态：进行中（2026-09-12），已完成持久化模型、CRUD 合同、接口用例选择器、拖拽排序和首批场景运行闭环；SQL/Redis/条件/循环、复杂自定义 HTTP 配置及场景级数据驱动仍未完成。
- 后端新增 Flyway V6：`scenarios` 保存名称、变量、设置和 revision；`scenario_steps` 保存父子关系、位置、类型、启停、引用方式、失败策略和步骤配置。首版只允许步骤引用接口用例，不允许场景引用场景；保存时校验重复位置、缺失父节点、循环和最大嵌套深度，并验证接口用例属于当前项目且未归档。
- REST 新增 `/api/v1/projects/{projectId}/scenarios` 列表、创建、详情、更新和归档接口；更新按 revision CAS，步骤采用事务内整体替换；场景运行接口会生成本次运行专用计划，不修改已保存资产。
- 前端 `ScenarioView` 已接入当前项目：无场景时可创建草稿，保存后重新加载场景和步骤树；支持步骤类型添加、接口用例下拉选择、引用/复制模式、基础失败策略字段承载、同流程拖拽排序和步骤树展示。新增 `scenarioApi` 合同测试。
- TDD/验证：`ScenarioValidatorTest`、`ScenarioRunPlanBuilderTest` 和 Runner 场景定向测试通过；隔离 Compose 重建当前 Web/API/Runner 镜像后，Playwright `f2-05-scenario.spec.ts` 1/1 通过，覆盖新建项目与环境、保存场景、WAIT 场景真实运行并查看 `PASSED` 报告、添加 HTTP 步骤、拖拽排序和再次保存。
- 自定义 HTTP 首片已接入：步骤属性面板可配置方法、路径和 NONE/JSON/TEXT 请求体，保存为受校验的嵌套 `plan`；运行计划补齐环境 baseUrl、场景变量作用域和默认空结构，Runner 复用同一单接口 JMeter 编译链执行。
- 运行切片边界：场景步骤暂不展开嵌套数据行，SQL/Redis/条件/循环和 SSE 进度尚未接入；Header/Cookie/断言/提取器编辑仍复用接口用例页面，尚未在自定义 HTTP 面板中展开。
- 项目级门禁（2026-09-12）重新通过：F0-03 合同检查；Maven shared-contracts 16、platform-api 30、runner-app 62；Web Vitest 23 个文件/74 项；typecheck、生产构建和 WSL Compose 配置检查均通过，门禁退出 0。JMeter 测试输出中的 `upgrade.properties` 缺失属于既有非阻塞警告，相关测试仍全部通过。

## F2-06（JDBC 数据源与 SQL 步骤首个切片）

- 状态：进行中（2026-09-12）；已完成数据源管理、SQL 结构校验、PostgreSQL/MySQL 双库真实容器验证和受控 JDBC 查询首片，尚未宣称完整 JMeter JDBC 映射和复杂 SQL 结果语义全部验收。
- Platform：新增 Flyway V7 `jdbc_data_sources`，按项目/环境保存 PostgreSQL 或 MySQL 连接元数据；密码只接受活动密钥名称，REST 支持列表、创建、更新、归档、恢复和连接测试，响应不包含密码明文。
- 场景合同：SQL 步骤要求 `dataSourceId`、SQL 文本、参数、提取器和断言；SELECT 可直接进入运行计划，其他语句必须同时设置允许写入与二次确认，否则服务端拒绝。运行计划只携带 `credentialRef` 密钥引用，且校验数据源属于本次运行环境。
- Runner：新增受控 JDBC SQL 执行器，支持环境/场景/数据行/前序提取变量参数绑定（优先级与 HTTP 执行器一致）、行列提取、行数/字段/集合包含断言，并把脱敏 JTL 摘要和提取值回传现有报告链路；同时新增安全的 JMeter `JDBCDataSource`/`JDBCSampler` JMX 映射测试产物。由于 JMeter 原生 `DataSourceElement` 不解析平台自定义的 `__autotestSecret` 密钥函数，运行时仍采用直接 JDBC，避免把密码写入 JMX。
- Web：环境页已新增 JDBC 数据源配置、密钥引用、连接测试；场景页 SQL 步骤已提供数据源选择、SQL 编辑和行数断言入口，未扩展 Redis/条件/循环。
- 新鲜验证（2026-09-12）：根目录 `scripts/verify-local.ps1` 一键门禁退出码 0；Maven 全量 shared-contracts 16、platform-api 32、runner-app 67（含 PostgreSQL 16 与 MySQL 8.4 Testcontainers 查询/提取/断言，以及安全 JMX 映射结构测试）全部通过；Web Vitest 23 个文件/74 项、typecheck、生产构建和 WSL Compose 配置检查全部通过。JMeter 测试输出中的 `upgrade.properties` 缺失属于既有非阻塞警告。
- 未完成边界：JMeter 原生 JDBC 直接执行（受密钥函数解析限制）、SQL 参数对象的完整类型映射、更多组合断言/写事务语义、报告 SQL 明细展示以及 F2-07 Redis 仍待继续。

## F2-07（受控 Redis Sampler 首个切片）

- 状态：进行中（2026-09-12）；已完成 Redis 数据源管理、连接测试、四种白名单命令的受控执行和场景步骤接入，完整 Redis 报告展示、TLS/认证实网覆盖和集合门禁仍待补齐。
- Platform：新增 Flyway V8 `redis_data_sources`，按项目/环境保存主机、端口、DB 编号、可选用户名、密钥引用和 TLS 选项；REST 支持列表、创建、更新、归档、恢复、连接测试，密码只通过活动密钥引用解析且响应不返回明文。
- 场景合同：REDIS 步骤只接受 GET、SET、DEL、EXISTS；Key/Value 支持变量占位；SET/DEL 必须同时满足 `allowWrite=true` 与 `confirmed=true`，未知命令、Lua 和任意命令文本在保存与运行计划阶段拒绝。
- Runner：新增受控 `RedisCommandExecutor` 和平台自研 `RedisSampler` JMeter 组件，支持结果提取、EXISTS/EQUALS/CONTAINS 断言及脱敏 JTL 摘要；主运行链使用直接 Jedis 执行以保持密钥隔离，JMX 产物使用临时 JMeter 属性引用而不写入密码。
- Web：环境页新增 Redis 数据源表单、密钥引用、TLS、连接测试；场景编排器新增 Redis 数据源选择、命令、Key/Value 和值断言入口。
- 新鲜验证（2026-09-12）：真实 Redis 7 Testcontainers 覆盖 GET、SET、DEL、EXISTS 和未确认写拒绝 2 项；JMeter Redis JMX 白名单/密钥引用结构测试 2 项；Redis 步骤校验 2 项；Platform 全量 32 项、Runner 全量 71 项通过。根目录 `scripts/verify-local.ps1` 一键门禁退出码 0，Web Vitest 24 个文件/75 项、typecheck、生产构建和 WSL Compose 配置检查全部通过。
- 未完成边界：Redis TLS/ACL 密码容器验收、完整 Redis 报告明细与跨步骤组合场景、F2-08 条件/循环/等待控制流仍待继续。

## F2-08（条件、循环和等待首个闭环）

- 状态：进行中（2026-09-12）；已完成无脚本条件分支、固定次数循环、列表遍历、条件循环、固定等待和父子步骤运行树首片，F2-09 的重试、取消和独立清理语义不在本轮范围。
- 配置与平台：新增 `ControlFlowStepValidator`，条件只允许标量比较和八种白名单运算符；循环支持 `FIXED`、`LIST`、`WHILE`，统一 `maxIterations` 上限 1000；拒绝 JMeter 函数、JSR223、BeanShell 和未闭合变量模板。运行计划现在携带 `parentId`、`position`、`branch` 和控制流 `plan`，并验证 Redis/SQL 数据源引用。
- Runner：`ExecutionPlanAdapter` 构造父子树；`RunnerWorker` 直接执行 THEN/ELSE 分支、固定/列表/条件循环，列表项写入前序提取上下文，禁用步骤和未命中分支不发起请求；循环达到最大次数失败并安全停止；等待期间仍按 100ms 切片响应取消。新增 `ControlFlowEvaluator`，优先使用前序提取值，再回退到数据行、用例、场景和环境变量。
- JMeter：`JmeterPlanCompiler.compileControlFlow` 只生成 IfController、LoopController、ForeachController、WhileController 结构金样，不生成任意脚本组件；主 Runner 仍使用受控直接执行语义，避免把用户表达式变成 JMeter 函数。
- Web：场景属性面板新增条件左值/运算符/右值、条件子分支、固定/列表/条件循环参数、最大次数和等待毫秒数；从条件或循环步骤添加子步骤会自动建立父子关系，子步骤可选择 THEN/ELSE。
- 新鲜验证（2026-09-12）：控制流校验 2 项、计划构建 3 项、Runner 控制流解析/Worker 场景分支/JMX 结构测试通过；WSL 根目录 `scripts/verify-local.ps1` 一键门禁退出码 0，Maven shared-contracts 16、platform-api 37、runner-app 75，Web Vitest 24 个文件/75 项、typecheck、生产构建和 Compose config 全部通过。
- 未完成边界：尚未覆盖复杂布尔表达式、嵌套场景引用、并发控制、条件循环与外部状态联动的真实业务夹具；控制流报告的专用明细展示和 F2-09 失败策略/重试/取消/独立清理计划待继续。

## F2-09（重试与取消后清理首片）

- 状态：进行中（2026-09-12）；已完成受限重试、取消感知的退避和清理分区首片，尚未宣称强杀后的“清理未执行”独立状态、主/清理双计划报告模型或所有失败类别筛选全部完成。
- 平台合同：`RETRY` 必须提供 `retry.maxAttempts`（2 到 5）和 `intervalMillis`（0 到 60000），运行计划携带该配置；前端“清理”入口保存为清理分区的受控 HTTP 步骤，条件子步骤的 `branch` 同步写入平台实际读取的步骤配置。
- Runner：每次重试保留首轮和最终轮结果，后续轮次使用 `stepId#attempt-N` 结果键；重试退避按 100ms 切片检查取消。主流程收到取消后停止继续执行主步骤，但清理根节点及其子步骤不再接收取消信号，仍按顺序尽力执行；清理失败通过自己的步骤结果保留，不能覆盖主流程失败或取消终态。
- 新鲜验证（2026-09-12）：`RunnerWorkerTest` 定向 13 项通过，新增覆盖“取消后清理执行且清理不接收取消信号”和“清理失败不替换主失败”；随后根目录 `scripts/verify-local.ps1` 一键门禁退出码 0，Maven shared-contracts 16、platform-api 38、runner-app 78，Web Vitest 24 个文件/75 项、typecheck、生产构建和 Compose config 全部通过。
- 报告补充：平台新增 `CleanupStatusResolver`，报告接口和报告页已提供 `cleanupStatus` 汇总卡片；状态只根据清理步骤结果推导，不覆盖主流程终态。
- 未完成边界：Runner 在任务领取前已处于取消状态时仍不会启动清理；宿主机/Runner 强制终止时还不能记录“清理未执行”专用状态；清理结果仍复用现有 `StepResult` 链，尚未形成独立 cleanup 计划/报告页签；数据行、集合及复杂嵌套清理组合留待后续场景门禁。

## F2-10（测试集合首个闭环）

- 状态：首片实现完成并补齐成员选择/报告元数据（2026-09-12），全量门禁通过；不宣称测试集合全部能力完成。
- 平台资产：新增 Flyway V9 `test_suites` 与 `test_suite_members`，支持项目内集合名称、默认环境、成员顺序、成员类型（`API_CASE`/`SCENARIO`）、启停状态、revision 乐观锁、归档和项目隔离。
- 运行链：新增集合 CRUD、环境校验、API 用例/场景引用解析和 `TEST_SUITE` 运行计划；Runner 按 `position` 稳定排序，顺序执行启用成员、跳过停用成员，复用已有 HTTP/场景执行及取消语义。集合内场景步骤的 `stepId` 会加上 `memberId/` 作用域，避免不同成员的同名步骤覆盖报告证据。
- 前端工作台：集合页已接入真实列表、成员树/顺序展示、启停切换、上下移动、revision 保存、环境选择和运行入口；提交运行后进入真实报告页，不直连目标服务。
- 成员选择补充：集合页支持创建空集合，加载项目内活动接口用例和场景，按名称/类型搜索后添加成员，支持移除、启停、排序并保存。
- 报告补充：集合运行计划将成员名称写入不可变 `executionPlan`；报告接口和报告页展示成员顺序、类型、目标 ID、名称和启停状态；集合提交运行后跳转报告页，报告页对 `PENDING/RUNNING` 运行轮询到终态。
- TDD/新鲜验证：成员选择器 Web 定向 4 项通过；成员报告解析与运行计划定向 2 项通过；ReportView 成员展示与成员过滤定向 1 项、SuiteView 运行后跳转定向 4 项通过；真实 Playwright `f2-10-suite.spec.ts` 在隔离 PostgreSQL/API/Web 环境最新 1/1 通过，覆盖创建集合、选择接口用例与基础场景成员、保存、提交运行并确认报告页收到 `runId`；Runner 成员作用域定向覆盖 2 项通过；随后根目录 `scripts/verify-local.ps1` 于 2026-09-12 重新通过，Maven shared-contracts 16、platform-api 47、runner-app 82，Web Vitest 27 个文件/82 项，typecheck、Vite production build 和 WSL Compose config 全部通过。`verify-platform-flow.ps1` 新增参数已通过 PowerShell 解析检查，Playwright spec 列表检查通过。
- Runner Compose 真实闭环补证（2026-09-12）：用临时合成配置构建并启动全新 Compose 项目、PostgreSQL/Runner 卷和三份镜像；`f2-10-suite.spec.ts` 在 Web→Platform API→Runner→JMeter 链路下 1/1 通过，并要求集合报告终态为 `PASSED`、成员卡片包含接口用例与基础场景两类名称。重启 Platform API/Web/Runner 后，使用同一 `runId` 重新查询报告仍为 `PASSED`，步骤数 3、集合成员元数据 2（`API_CASE` 与 `SCENARIO` 各一）；验收后已精确删除项目容器、网络、卷和合成配置/元数据文件。
- 报告补充（2026-09-12）：步骤结果接口新增 `memberId`，后端按集合成员和稳定结果键解析成员归属；报告页支持按成员过滤步骤树和证据面板，保留“全部成员”视图。
- 复杂集合门禁补证（2026-09-12）：新增测试专用 Compose 覆盖层和受控 HTTP 目标，真实 Playwright `f2-10-complex-suite.spec.ts` 1/1 通过。单一集合报告包含登录、提取、业务、SQL、Redis 和清理步骤，6 个步骤全部 `PASSED`；清理后 Redis 哨兵键不存在；重启 Platform API 后用同一 `runId` 查询报告仍为 `200/PASSED`。为让这条链路可复现，Runner 镜像加入自定义 JMeter 组件薄 JAR，密钥文件使用受控 `__autotestSecret` 函数，JMeter 输出改为 XML JTL 以回传受控提取元数据，并把 JTL 中的断言失败归一为运行失败。
- 组件修复后的最终门禁（2026-09-12）：根目录 `scripts/verify-local.ps1` 退出码 0；Maven shared-contracts 16、platform-api 47、runner-app 84，Web Vitest 27 个文件/82 项，typecheck、Vite production build 和 WSL Compose config 全部通过。复杂夹具的 Compose 项目、容器、网络、卷和临时元数据已精确删除。
- 未完成边界：当前真实集合闭环已覆盖 API_CASE、基础 SCENARIO、变量提取、SQL、Redis 和清理组合；定时任务、SSE 进度、多 Runner 调度、强制终止后的“清理未执行”专用状态以及全部请求形态矩阵仍待后续补齐，F2-10 只关闭本任务的集合编排与复杂夹具门禁，不宣称平台全部需求完成。

## F3-01（兼容式模型适配与结构化领域工具首片）

- 状态：首个后端纵向切片已实现（2026-09-12），不宣称 AI 工作台全流程完成。
- 持久化：新增 Flyway V11，保存模型配置、AI 会话和消息；模型配置只接受兼容式地址、模型名和密钥引用，不接收 API Key 明文，消息入库前统一脱敏。
- 模型适配：新增 OpenAI-compatible Chat Completions 客户端，支持文本响应和工具调用响应；新增确定性的 `FakeChatModelClient` 作为契约测试替身；上游非 2xx、超时或解析失败统一转换为脱敏错误事件。
- 结构化工具：固定注册 `list_projects`、`list_api_cases`、`list_scenarios`、`get_run_report`、`create_draft_patch` 五个工具。前四个只返回受控元数据/报告摘要，Patch 只生成 `PENDING_REVIEW` 建议，不写接口、用例、场景或运行表；工具注册表不提供数据库、JMeter、Runner、文件系统、Shell 或脚本工具。
- API：新增模型配置 CRUD、项目级 AI 会话列表/详情/创建和消息 SSE 端点；SSE 事件包含文本、工具调用、工具结果、错误和结束事件，继续使用登录、CSRF 和统一错误边界。
- 新鲜验证：F3-01 定向 Maven 测试 9 项通过（工具白名单、脱敏、Fake 工具调用、Patch 不落库、模型配置校验、兼容客户端本地 HTTP 解析、会话工具分发）；`PlatformApiPostgresqlIntegrationTest` 与 OpenAPI F1-05 合同 4 项通过，Flyway 11 次迁移和二次启动幂等通过。
- 根门禁补充验证（2026-09-12）：`scripts/verify-local.ps1` 退出码 0；Maven 全量 shared-contracts 16、platform-api 91、runner-app 84；Web Vitest 30 个文件/88 项，前端类型检查、生产构建和 WSL Docker Compose 配置检查全部通过。
- 未完成边界（F3-01 当时口径）：AI 首页真实 API 接入、失败报告解释和 SSE 浏览器验收随后由 F3-04 补齐首片；模型配置页面、提示注入专项安全门禁和自动运行/通知仍待 F3-05 及后续任务。

## F3-02（Curl 与 OpenAPI 导入首个闭环）

- 状态：解析、预览和确认落库首片完成（2026-09-12），不宣称导入能力覆盖全部 OpenAPI 方言或文件上传场景。
- 解析器：`CurlImportParser` 只接受受控常用选项、HTTP/HTTPS URL、查询参数、Header、Cookie、JSON/TEXT/URLENCODED body；拒绝 Shell 替换、管道、重定向、未知选项和未闭合引号。`OpenApiImportParser` 支持 OpenAPI 3 JSON/YAML、首个 server、路径/操作、参数、JSON/TEXT/URLENCODED 请求体；不解析远程 `$ref`，不发起外部网络请求。
- 脱敏：Authorization、Cookie、API Key、Token 等敏感值在中间结构中只保留 `${secret:...}` 占位符并返回绑定密钥警告，不把原始值写入预览、日志或数据库。
- 预览与确认：新增 `/api/v1/projects/{projectId}/imports/curl/preview`、`/openapi/preview` 和 `/confirm`；预览按方法+URL及名称标记新增、精确匹配、名称冲突，默认 CREATE 或 SKIP，UPDATE 必须显式选择。预览保存在带用户、项目、过期时间和修订校验的临时令牌中，确认使用已有定义/用例的 revision CAS；预览阶段不落库，确认在单事务中创建或更新定义与默认用例。
- 前端：新增 `web/src/api/import.ts`，为后续 AI 工作台和导入页面提供 Curl/OpenAPI 预览、冲突选择和确认 API 合同；本轮不新增导入页面或字段级 Diff。
- 新鲜验证（2026-09-12）：Curl 20 个代表输入、OpenAPI JSON/YAML/请求体 3 类夹具及安全负向共 26 项解析测试通过；服务预览不写库/显式确认创建与冲突默认跳过 2 项通过；真实 PostgreSQL HTTP 回归 2 项通过，确认前定义数量保持不变；前端导入 API 2 项通过。随后根目录 `scripts/verify-local.ps1` 退出码 0，Maven shared-contracts 16、platform-api 85、runner-app 84，Web Vitest 28 个文件/84 项，typecheck、Vite production build 和 WSL Docker Compose 配置检查全部通过。
- 未完成边界：暂无导入页面、复杂 OpenAPI `$ref`/回调/安全方案、Multipart/文件上传、服务重启后的预览令牌恢复；这些内容留给后续请求形态任务。

## F3-03（AI 草稿 Patch、字段级差异与人工确认首片）

- 状态：首片实现完成（2026-09-12），AI 首页接入和失败报告解释首片随后由 F3-04 交付；不宣称自动运行已完成。
- Patch Schema：支持 `API_DEFINITION`、`API_CASE`、`SCENARIO` 三类目标，操作限制为 `add`、`replace`、`remove`；禁止修改 `id`、`projectId`、`revision`、`definitionId` 等受保护字段，路径和值经过 JSON Pointer、Shell/脚本片段和敏感字段引用校验。
- 预览服务：新增 `AiPatchService` 和 `/api/v1/projects/{projectId}/ai/patches/preview`；读取当前资产生成旧值/新值、增加/修改/删除、风险标记和校验错误，预览只存短期用户/项目绑定令牌，不调用写入服务。
- 确认服务：新增 `/confirm`；确认时重新读取当前资源并检查 revision，复用定义、用例、场景领域校验与 CAS 写入，单个 Patch 在一次事务中保存，成功后令牌单次消费，重复确认返回 404；资源变化或项目归档时拒绝确认。
- AI 工具：`create_draft_patch` 现在返回目标、基线和受控操作结构，并限制操作数量和基本格式；仍不提供数据库、JMeter、Runner、文件系统、Shell 或自动运行工具。
- 前端：新增 `web/src/api/aiPatch.ts` 和 `AiPatchDiff.vue`，展示字段路径、旧值、新值、变化类型、风险标记、校验错误；关闭预览只触发取消事件，不落库，确认动作交由上层调用令牌接口。
- 新鲜验证（2026-09-12）：`AiPatchServiceTest` 5 项、`AiAgentContractTest` 5 项、OpenAPI 合同 3 项通过；真实 WSL Docker/PostgreSQL HTTP 回归 3 项通过，覆盖预览不写库、确认 revision+1、重复确认拒绝、陈旧 revision 和 Shell 片段拒绝；前端 AI Patch API/Diff 测试 4 项通过。随后根目录 `scripts/verify-local.ps1` 退出码 0：Maven shared-contracts 16、platform-api 91、runner-app 84；Web Vitest 30 个文件/88 项，类型检查、生产构建和 WSL Docker Compose 配置检查全部通过。
- 未完成边界：Patch 令牌当前为进程内短期存储，服务重启后预览失效；前端 Diff 已由 F3-04 接入 AI 首页，但暂不支持 Multipart 文件、复杂 OpenAPI 引用、跨项目引用和自动确认/自动运行。

## F3-04（AI 工作台与失败报告解释首片）

- 状态：首片实现完成，根门禁通过（2026-09-12）。
- 后端：`get_run_report` 工具现在返回脱敏且截断的请求、响应、断言和错误证据；新增项目运行列表 `GET /api/v1/projects/{projectId}/runs?limit=`，仅供 AI 失败上下文选择，不增加运行能力。Fake 模型补充 Curl Patch 和失败报告工具事件。
- 前端：新增 AI 模型/会话/SSE 客户端；AI 工作台接入当前项目、模型状态、会话历史、运行选择、SSE 消息、失败证据卡片和 F3-03 Diff 预览/确认。未配置模型、模型异常和 SSE 错误均显示可理解反馈。
- 定向验证：AiAgentContractTest 7 项、RunPostgresqlIntegrationTest 1 项（WSL Docker/PostgreSQL）和 Web AI API/工作台测试 5 项通过；随后根目录 `scripts/verify-local.ps1` 退出码 0，Maven shared-contracts 16、platform-api 93、runner-app 84，Web Vitest 32 个文件/92 项，类型检查、生产构建和 WSL Docker Compose 配置全部通过。
- 未完成边界：模型配置页面仍是原型，Patch 预览令牌仍为进程内短期存储；未实现自动确认/自动运行、跨项目上下文、后台 SSE 恢复和提示注入专项门禁，F3-05 待后续任务。原任务要求的真实云模型/Compose/Playwright“生成→预览→确认→运行→解释”全流程尚未作为本轮首片的完成证据。

## F3-05（AI 安全门禁首片）

- 状态：安全实现、定向门禁和根门禁完成（2026-09-12），不宣称云模型及部署级安全验收全部完成。
- 上下文保护：`AiPromptSanitizer` 对普通赋值、Authorization 和 JSON 引号键统一替换敏感值，并限制单条上下文 20,000 字符；`AiSessionService` 按最新消息构造总量不超过 20,000 字符的模型上下文。
- 权限保护：工具注册表继续只暴露项目/用例/场景元数据、脱敏报告和待确认 Patch；未知 Runner、Shell、通知、SQL 写入和运行工具 fail closed；Patch 内容继续经过脚本、敏感字段和业务结构校验，确认前不调用写入服务。
- 审计证据：会话消息保留角色、事件类型和工具名，工具结果入库前再次脱敏；新增注入样本、危险工具、上下文上限、最新上下文和工具结果审计测试。
- 定向验证：`AiSecurityContractTest` 5 项、`AiAgentContractTest` 7 项、`AiSessionServiceTest` 1 项通过；真实 WSL PostgreSQL 脱敏回归 1 项通过。
- 根门禁验证：`scripts/verify-local.ps1` 一键门禁退出码 0；Maven shared-contracts 16、platform-api 94、runner-app 84，Web Vitest 32 个文件/92 项，类型检查、生产构建和 WSL Docker Compose 配置检查全部通过。
- 未完成边界：真实云模型抓包、部署级提示注入集和 Playwright 安全门禁仍待后续环境验收；不宣称 F3-05 全部验收完成。

## F4-01（Cron、CI/API 触发和通用 Webhook 首片）

- 状态：首片实现完成并通过根目录一键门禁（2026-09-12）；不宣称通知渠道和调度页面的部署级验收全部完成。
- 数据与调度：新增 Flyway V12 的 `schedules`、`trigger_tokens`、`webhook_configs` 表；Cron 支持 5/6 段表达式与时区校验，调度记录支持启停、下次运行时间、集合/环境归属和 revision CAS。当前使用单实例 `SchedulePoller`，到期运行复用既有运行幂等键。
- CI/API 触发：项目级触发令牌只持久化 SHA-256 哈希，创建响应只展示一次明文；触发接口免登录但要求令牌，重复幂等键返回同一运行而不重复建 run，项目、集合和环境引用仍由服务端校验。
- Webhook：配置复用项目 Secret 引用，服务端仅在发送时短暂解析；请求使用 HMAC-SHA256 签名，正文只含运行标识、状态和脱敏摘要，失败最多重试三次且不改变运行终态。Runner 完成后通过内部受保护回调通知平台，再由平台按事件发送 Webhook。
- 新鲜验证：`F401SecurityContractTest` 4 项、`ScheduleServiceTest` 2 项、`WebhookServiceTest` 1 项、`RunFinishedWebhookControllerTest` 1 项、Runner `RunCompletionNotifierTest` 1 项和 `RunnerWorkerTest` 16 项通过；真实 WSL PostgreSQL `F401ScheduleTriggerPostgresqlIntegrationTest` 1 项通过，覆盖关闭调度、令牌哈希、重复触发幂等和 Webhook Secret 不落明文。
- 根门禁验证：`scripts/verify-local.ps1` 退出码 0；Maven Surefire 报告为 shared-contracts 16、platform-api 107、runner-app 84，Web Vitest 32 个文件/92 项，前端类型检查、生产构建和 WSL Docker Compose 配置检查全部通过。
- 期间修复：PostgreSQL 对可空 UUID 条件参数无法推断类型的问题已通过调度和 Webhook 名称检查的显式分支修复，并由真实 PostgreSQL 回归覆盖。
- 未完成边界：尚未完成完整 Compose 中 Runner→平台→外部 Webhook 接收端的部署级端到端验收，尚未新增调度配置页面的 Playwright 验收；未实现邮件/钉钉/飞书/企微、报告导出、归档、备份、多实例抢占、租约或 fencing。后续进入 F4-02 前需继续保持这些边界明确。

## F4-02（HTML/Allure 导出与运行保留策略首片）

- 状态：首片实现完成并通过根目录一键门禁（2026-09-12）；不宣称 MinIO 对象归档和诊断制品下载已完成。
- 原生报告导出：新增报告导出服务和下载接口，读取已脱敏的 `RunReport` 生成 HTML；HTML 对步骤状态、请求、响应和断言做转义，避免把报告内容当作脚本执行。新增 Allure 结果 ZIP，每个步骤生成标准 `*-result.json` 并映射 `PASSED/FAILED/SKIPPED/CANCELED/INTERRUPTED` 状态。
- 保留策略：新增 Flyway V13 `project_retention_settings`，默认 30 天，允许 1–3650 天，更新使用 revision CAS。清理事务先删步骤结果再删运行，只处理已结束状态，保留 `PENDING/RUNNING`；当前清理通过项目级 API 手动触发。
- 新鲜验证：TDD 红灯先验证导出和保留领域类缺失时失败；实现后 `ReportExportServiceTest` 2 项、`RetentionServiceTest` 2 项通过；真实 WSL PostgreSQL `F402ReportRetentionPostgresqlIntegrationTest` 1 项通过，覆盖导出脱敏、Allure ZIP、过期终态删除和活动运行保留。
- 根门禁验证：`scripts/verify-local.ps1` 退出码 0；Maven shared-contracts 16、platform-api 113、runner-app 85，Web Vitest 32 个文件/92 项，前端类型检查、Vite 生产构建和 WSL Docker Compose 配置检查全部通过。
- 未完成边界：本切片尚未引入 MinIO SDK/对象归档，未提供 JMX/JTL/日志诊断文件下载、导出物持久化、自动定时清理、报告归档和恢复保护；这些留给 F4-02 后续切片或 F4-03，当前不宣称 F4-02 全部需求已完成。

## F4-03（审计、Runner 状态与运维恢复首片）

- 状态：首片实现完成并通过根目录一键门禁（2026-09-12）；不宣称 F4-03 的 MinIO/附件灾备和完整恢复演练已完成。
- 审计：新增 Flyway V14 `audit_events`，记录登录成功、密钥创建/替换/归档、运行创建/取消、AI Patch 确认和 Webhook 投递；审计元数据递归遮蔽 Token、Authorization、Cookie、密码等敏感键和值，可按项目、动作和 `traceId` 查询，并保留 `runId/stepId` 关联字段。
- Runner 可观测性：新增 `runner_status`、内部 Token 保护的心跳接口和登录用户可访问的 `/api/v1/runners/status`；最近 30 秒心跳为 `ONLINE`，超时为 `OFFLINE`。Runner 配置支持稳定 UUID/版本标识，常驻轮询周期发送不含数据库密码和回调令牌正文的最小心跳。
- 运维交付：新增 `deployment/scripts/backup-postgres.sh/.ps1` 和显式确认的 `restore-postgres.sh/.ps1`，以及中文运维恢复说明；脚本不回显数据库密码，恢复不自动删除卷或重启服务。
- 新鲜验证：审计/在线超时领域单测 2 项、Runner 心跳客户端单测 1 项、WSL Testcontainers PostgreSQL `F403AuditRunnerPostgresqlIntegrationTest` 1 项通过；迁移集成测试已确认 Flyway 15 次迁移和二次启动幂等；脚本 Bash/PowerShell 语法检查通过；最新根目录门禁曾以 Maven shared-contracts 20、platform-api 119、runner-app 88、Web Vitest 32 个文件/92 项通过，前端类型检查、生产构建和 WSL Docker Compose 配置检查全部通过。
- 未完成边界：当前 Compose 没有 MinIO；附件/JMX/JTL/日志对象备份、远端归档、恢复演练和 Runner 工作目录恢复未交付。登录失败审计、全量 HTTP 访问日志、队列深度的真实动态采集和审计前端页面仍待后续切片；PostgreSQL 恢复脚本尚未在本机业务卷上执行覆盖性演练。

## F4-04（参考能力夹具与安全验收首片）

- 状态：首片已完成并通过 Luna→Sol 双模型门禁（2026-09-12）；十类夹具仍是参考映射清单，不宣称已经全部在 Compose 中真实运行，也不宣称 F4-05 已完成。
- 参考夹具：新增 `test-fixtures/pytest-auto-api/manifest.json`，只记录外部项目 README/源码的相对证据位置、脱敏的平台资产摘要和报告证据字段；已按需求 13.4 固定为 GET+Query+缓存变量、POST JSON+多断言、多接口依赖和 JSONPath、多数据行、SQL 断言、Redis 读写、文件上传、动态 UUID/时间/随机数据、前置造数和失败后清理、通知和报告导出十类能力。
- 自动门禁：新增 `ReferenceFixtureContractTest`，检查十类夹具数量与唯一性、来源锚点、平台资产和报告证据字段、安全用例状态，以及清单不包含已知真实内网地址、手机号或疑似明文密钥。
- 中文说明：新增 `docs/reference-acceptance/F4-04-参考能力验收.md`，明确“参考证据→平台资产→报告证据”的映射和未完成边界；旧 YAML 仍不直接导入或执行。
- 新鲜验证（2026-09-12）：TDD 红灯阶段清单缺失按预期失败；补齐清单后 `ReferenceFixtureContractTest` 1 项通过。随后最新根目录门禁以 Maven shared-contracts 20、platform-api 119、runner-app 95、Web Vitest 32 个文件/92 项通过，前端类型检查、生产构建和 WSL Docker Compose 配置检查全部通过。已核对外部只读 README/源码证据，未复制其代码、证书、附件或业务数据。
- SSRF 首片：新增共享 `TargetAllowlist`（域名、通配子域、IPv4/IPv6、HTTP/HTTPS 格式校验）；项目 V15 持久化 `target_allowlist_json`，项目管理 API 和前端表单支持配置；运行创建时将规范化规则和 `targetPolicyRequired` 固化到运行 JSON，并校验 baseUrl/绝对 URL；Runner 对带策略标记的计划再次校验，场景/集合嵌套计划继承规则；补充了明确允许内网目标和运行计划持久化隔离回归。
- 重定向逐跳门禁：新增 `GuardedHttpSampler`/`GuardedHttpClient`，统一继承 JMeter `HTTPSamplerBase` 并委托 `HTTPHC4Impl` 单跳执行，固定 `auto_redirects=false`，每一跳在发送前检查白名单；缺失、空白、非法策略 fail-closed，失败样本固定为 `TARGET_NOT_ALLOWED` 且脱敏 URL。允许→拒绝、允许→允许→拒绝、全允许链均有真实 HTTP 证据，未授权端点请求数为 0。
- 真实交付验证：Dockerfile 安装自定义 `saveservice.properties`；components classifier 包含 `GuardedHttpSampler`、`GuardedHttpClient`、`TargetAllowlist` 及 `Decision`；修复镜像 CLI 成功加载 Guarded JMX，JTL 有 1 条 `TARGET_NOT_ALLOWED` 样本且不含 query。Runner 全量 95 项测试通过，容器 CLI 回归 1/1 通过；Sol high 最终复审结论 `PASS`。
- 安全边界：密钥脱敏、危险 SQL、文件路径、提示注入和脚本组件已有现有测试映射；项目白名单非法输入和旧 revision 已有专用 HTTP 回归，独立并发 CAS 压测仍未补齐；DNS rebinding 解析地址校验和 F4-05 其他能力不属于本首片。
