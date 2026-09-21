# F4-01 Cron、CI/API 触发和通用 Webhook 实施计划

## 目标

让测试集合可以通过已校验的 Cron 自动创建运行，也可以用项目级触发令牌从 CI/API 幂等触发；运行完成后的通知首片使用通用 Webhook，签名请求且只发送脱敏摘要。

## 本轮范围

- 新增项目级调度记录：集合、环境、Cron、启停、下次运行时间和 revision。
- 新增项目级触发令牌：只保存哈希，创建响应只展示一次明文令牌；触发接口不建立登录会话。
- 新增通用 Webhook 配置和签名发送服务：密钥使用已有项目 Secret 引用，正文只包含运行标识、状态和脱敏摘要。
- 单实例轮询到期调度，复用既有 `TestSuiteRunService` 和运行幂等键；不引入 Redis、队列或多 Runner 调度。

## 明确不做

- 不实现邮件、钉钉、飞书、企微适配器；不实现报告导出、归档、备份。
- 不保存触发令牌或 Webhook Secret 明文，不把完整请求/响应/日志发送到外部系统。
- 不做多实例抢占、租约、fencing、Kubernetes 或分布式调度；当前仍是单 Runner/单平台实例边界。

## 验收条件

1. 非法 Cron、归档集合/环境和错误项目引用被拒绝；启停和 revision CAS 生效，并可计算下次运行时间。
2. 相同项目令牌与幂等键重复触发只创建一个运行；无效令牌、归档集合或关闭调度不得创建运行。
3. Webhook 请求带 HMAC 签名，失败最多重试三次；正文无 Secret 明文、完整响应和日志内容。
4. 后端定向测试、真实 WSL PostgreSQL 触发回归和根门禁通过；尚未覆盖的部署级通知由进度账本明确记录。

## 执行清单

- [x] 先写 Cron、令牌幂等、Webhook 脱敏和危险输入红测试。
- [x] 新增 V12 迁移及调度/令牌/Webhook 最小领域实现。
- [x] 接入 API、单实例到期轮询和签名重试。
- [x] 执行定向测试、真实 PostgreSQL 回归与根门禁。
- [x] 更新进度账本并记录未完成边界。

## 当前验证记录

- 定向后端测试通过：`F401SecurityContractTest` 4 项、`ScheduleServiceTest` 2 项、`WebhookServiceTest` 1 项、`RunFinishedWebhookControllerTest` 1 项。
- Runner 定向测试通过：`RunCompletionNotifierTest` 1 项、`RunnerWorkerTest` 16 项；完成回调只发送运行状态摘要，不发送 JTL、JMX、完整请求响应或日志。
- 真实 WSL PostgreSQL 回归通过：`F401ScheduleTriggerPostgresqlIntegrationTest` 1 项，覆盖关闭调度、令牌只存哈希、重复幂等触发只建立一个运行、Webhook Secret 引用不落明文。
- 根目录 `scripts/verify-local.ps1` 一键门禁退出码 0；当前 Maven Surefire 报告为 shared-contracts 16、platform-api 107、runner-app 84，Web Vitest 32 个文件/92 项，前端类型检查、生产构建和 WSL Compose 配置检查通过。
- 期间修复了 PostgreSQL 对可空 UUID 条件参数无法推断类型的问题：调度和 Webhook 名称检查在 `id` 为空与非空分支分别执行，避免依赖数据库隐式推断。

## 未完成边界

- 本轮只完成单实例调度、项目级 CI/API 触发和通用 Webhook 首片；没有实现邮件、钉钉、飞书、企微、报告导出、归档或备份。
- 尚未进行完整 Compose 中 Runner 完成回调到外部 Webhook 接收端的部署级端到端验收，也未进行 Playwright 的调度配置页面验收；当前证据是后端真实 PostgreSQL、Runner HTTP 单元/定向测试和根门禁。
- 未引入 Redis、队列、租约、fencing 或多实例抢占；调度轮询仍是单平台实例边界。
