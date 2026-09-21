# F1-09 实施报告：基础结果采集和原生报告

## 状态与边界

- 状态：**第一条结果采集纵向切片已落盘，仍未完成 F1-09 全部验收**（2026-09-12）。
- 已实现 JTL CSV 解析、步骤 ID 提取、查询参数脱敏、`step_results` 幂等入库和项目隔离的报告查询。
- Worker 已可以在一次执行成功后解析 JTL，并通过固定 `X-Runner-Token` 回传步骤摘要；前端 ReportView 真实接入、SSE、导出、Allure、场景树和附件对象存储仍未实现。

## 已实现

- `runner-app`：`JtlParser` 解析 JMeter CSV JTL 的 `label/elapsed/responseCode/responseMessage/success/failureMessage/URL`，从 `HTTP Request [stepId]` 提取稳定步骤标识；对 token、password、secret、api-key、authorization、cookie 查询参数统一替换为 `***`，长文本截断。
- `runner-app` 新增 `JtlResultUploader`，以 `runId + stepId + 序号` 生成稳定 `resultKey`，不记录回传响应正文；`RunnerWorker` 可注入该上传器，在 JMeter 结束后完成 JTL→平台摘要回传。
- `platform-api`：复用 V1 `step_results` 表，新增 `StepResultRepository`、`ReportSanitizer`、`ReportService` 和 `ReportController`。
- 结果回传：`POST /api/v1/internal/runs/{runId}/step-results`，使用配置的 `X-Runner-Token`，以 `(runId,resultKey)` 幂等；重复回传返回同一结果 ID。
- 报告查询：`GET /api/v1/projects/{projectId}/runs/{runId}/report`，只允许项目路径匹配的运行，返回运行状态和步骤结果。
- 入库前统一脱敏：敏感字段固定掩码、URL 敏感查询参数替换，正文单字段最大 1 MB。

## 验证证据

| 范围 | 命令/结果 |
| --- | --- |
| JTL 解析 | `mvn.cmd -pl runner-app -am -Dtest=JtlParserTest -Dsurefire.failIfNoSpecifiedTests=false test`：2 项通过；覆盖成功、断言失败、连接失败和 token 查询脱敏。 |
| Runner 回传 | WSL `mvn -pl runner-app -am clean test -Dsurefire.failIfNoSpecifiedTests=false`：共享契约 16 项、Runner 23 项通过；覆盖 JTL 上传器、Worker 上传分支和后续 Runner 配置回归。 |
| Repository | WSL `mvn -pl platform-api -am -Dtest=StepResultRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test`：1 项通过；Testcontainers PostgreSQL 验证重复 key 只保留一行。 |
| API 闭环 | WSL `mvn -pl platform-api -am -Dtest=ReportPostgresqlIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`：1 项通过；真实登录、运行创建、Runner Token 回传两次、报告查询和明文哨兵检查通过。 |
| 回归 | WSL `mvn -pl platform-api -am test -Dsurefire.failIfNoSpecifiedTests=false`：共享契约 16 项、平台 API 21 项通过。 |

## 未完成项

- Worker 的回传客户端和 JTL→`StepResultWrite` 最小映射已完成，但尚未接入容器启动配置、真实固定 JMeter 镜像到 Platform API 的网络联调；这仍是部署级验收项。
- Web `ReportView.vue` 仍为原型数据，尚未根据报告 API 展示真实步骤、请求/响应和断言证据。
- 当前报告只保存摘要，不保存响应附件，也没有 SSE 推送和离线导出；这些属于后续 F1-09/F1-10 子任务，不在本轮冒充完成。
