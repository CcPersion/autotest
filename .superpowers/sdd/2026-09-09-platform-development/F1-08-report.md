# F1-08 实施报告：单 Runner 数据库队列和 JMeter 子进程

## 1. 状态与边界

- 状态：**Platform API、单 Runner 队列/进程和一次轮询 Worker 已落盘，Sol 复审待补**（2026-09-11）。
- 本轮复用 V1 `runs` 表，实现创建、查询、取消、幂等键和安全执行计划校验；Runner 实现单库原子领取、启动恢复、JMeter 子进程非零退出和取消轮询基础。
- 不实现 F1-09 的 JTL 解析、结果报告、SSE；不实现多 Runner 租约、fencing、Kafka、Kubernetes、场景执行或新的执行引擎。

## 2. 已实现内容

- `platform-api` 新增 `RunController`、`RunService`、`RunRepository` 和 `RunRecord`：
  - `POST /api/v1/projects/{projectId}/runs` 创建 PENDING 运行；同项目 `idempotencyKey` 重复请求返回已有记录。
  - `GET /api/v1/projects/{projectId}/runs/{runId}` 查询运行。
  - `POST /api/v1/projects/{projectId}/runs/{runId}/cancel` 取消 PENDING，RUNNING 则写入取消请求。
  - 校验项目/环境归属、归档状态、目标类型、JMeter 版本 5.6.3、执行计划为对象，并递归拒绝 password/secret/token 等明文字段。
- 新增 `V3__extend_runs_for_runner.sql`，为已有 `runs` 表补充 `exit_code`、JMX/JTL/日志路径和 `cancel_requested`；没有新表或后端执行引擎。
- `runner-app` 新增 `RunQueueRepository`：
  - `SELECT ... FOR UPDATE SKIP LOCKED` 后以状态条件更新，实现单 Runner 原子领取。
  - 启动时可将遗留 RUNNING 标记为 INTERRUPTED。
  - 保存 PASSED/FAILED/CANCELED 终态、退出码和产物路径。
- `JmeterProcessRunner` 固定使用参数列表启动 JMeter CLI，不经过 shell；返回非零退出码而不杀死 Runner，并支持取消请求时先 `destroy`、超时后 `destroyForcibly`。
- 新增 `ExecutionPlanAdapter`，将保存的 JSON 计划映射为 F1-07 的 `JmeterPlan`，保留类型化标量变量并在 Runner 侧再次拒绝敏感明文。
- 新增 `RunnerWorker.runOnce()`，按 `runId` 创建隔离工作目录，串起“领取→敏感校验/适配→JMX 编译→JMeter 子进程→终态落库”；编译异常写入不含异常消息的安全诊断并保存失败状态，取消请求不启动 JMeter。

## 3. TDD 与验证证据

| 阶段 | 命令/结果 |
| --- | --- |
| 红灯 | 先加入 `RunPostgresqlIntegrationTest`、`RunQueueRepositoryTest`、`JmeterProcessRunnerTest`；生产类不存在时 Runner 测试编译失败，符合预期。 |
| Platform API 绿灯 | WSL `mvn -pl platform-api -am test -Dtest=RunPostgresqlIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`：Testcontainers PostgreSQL 16 真实启动，创建/幂等/查询/取消 1 项通过；Flyway 真实应用 3 个迁移。 |
| Runner 绿灯 | WSL `mvn -pl runner-app -am test -Dtest=RunQueueRepositoryTest,JmeterProcessRunnerTest,ExecutionPlanAdapterTest,RunnerWorkerTest -Dsurefire.failIfNoSpecifiedTests=false`：8 项通过；覆盖原子领取/恢复、非零子进程、计划适配、成功/取消/编译失败 Worker 分支。 |
| 编译 | `mvn -pl platform-api -am -DskipTests compile`：Platform API 编译成功；上述 Runner 定向测试同时完成生产编译。 |

## 4. 已知未完成项

- `RunnerWorker` 当前提供一次轮询编排，尚未接入独立容器的常驻进程入口、数据库连接配置和 Compose 运行命令；因此尚未宣称部署后会自动消费队列。
- 尚未完成真实 Platform API → Runner → 固定 JMeter 镜像的端到端运行、取消和重启恢复验收；本报告不把 API 测试、队列测试或 Worker 单测夸大为部署级闭环。
- 尚未覆盖真实产物目录清理、路径遍历拒绝、密钥解密/变量替换和 JTL 结果适配；这些需在后续受控范围内补齐，不属于 F1-09 报告功能。
