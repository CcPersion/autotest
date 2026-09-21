# F3-04 AI 工作台与失败报告解释实施计划

## 目标

把现有 AI 原型首页接入真实 Platform API，形成一条可审阅闭环：选择项目和任务 → 创建 AI 会话 → 通过 SSE 接收模型文本/工具事件 → 对生成的 Patch 调用已有差异预览 → 用户确认后保存；失败分析只读取脱敏运行报告，输出步骤证据引用和排查建议。

## 本轮范围

- 新增前端 AI API：模型配置、项目会话、会话详情和 POST SSE 消息解析。
- AI 首页使用当前项目与可选运行编号；会话消息按轮次展示，工具结果进入结构化结果卡片。
- `get_run_report` 工具补充步骤请求摘要、响应摘要、断言和错误的脱敏截断证据。
- Fake 模型为 Curl/失败分析提供确定性工具事件，便于本地和 Playwright 验收；不改变云模型工具合同。
- 生成 Patch 结果可直接转已有 `AiPatchService.preview`，确认动作仍由 `AiPatchDiff` 完成。
- 增加运行列表读取接口，仅用于失败报告上下文选择，不增加运行或调度能力。

## 明确不做

- 不自动确认 Patch、自动运行、发送通知或执行脚本/SQL/Runner。
- 不持久化模型 API Key 明文；不把密钥、完整请求/响应或附件发送给模型。
- 不实现多模型编排、重试队列、后台 SSE 恢复和跨项目上下文。

## 验收条件

1. AI 首页在有可用模型配置时能创建会话并展示历史消息；SSE 的 TEXT、TOOL_CALL、TOOL_RESULT、ERROR、DONE 事件均有明确状态。
2. Curl 任务收到结构化 Patch 后能生成真实字段差异预览；关闭预览不落库，确认沿用 F3-03 的 revision/CAS。
3. 失败分析必须携带真实 `projectId/runId`，工具输出至少包含失败步骤 `stepId/resultKey/status/assertions/error`，且敏感值被遮蔽或截断。
4. 无模型、模型异常、无运行或 SSE 中断时页面给出可理解错误，普通接口/报告页面不受影响。
5. 前端定向测试、后端工具合同测试、WSL PostgreSQL HTTP 回归和根目录一键门禁均通过。

## 执行清单

- [x] 先写后端工具与运行列表红测试。
- [x] 实现脱敏报告证据和运行列表 API。
- [x] 先写前端 AI API/SSE 与工作台交互红测试，再替换原型本地状态。
- [x] 接入 Patch 预览和失败上下文选择，补组件测试。
- [x] 执行 WSL 定向测试与根门禁，更新进度账本。

## 验证记录

- WSL Docker/PostgreSQL：`RunPostgresqlIntegrationTest` 1 项通过，运行列表与取消/幂等回归通过。
- 后端合同：`AiAgentContractTest` 7 项通过；根 Maven 全量 shared-contracts 16、platform-api 93、runner-app 84 项通过。
- 前端：AI API/SSE 与工作台定向 5 项通过；根 Vitest 32 个文件/92 项通过，typecheck、Vite production build、WSL Compose config 全部通过。
- 验收边界：本轮是可独立测试的首片；原任务要求的真实云模型/Compose/Playwright“生成→预览→确认→运行→解释”全流程，以及模型配置页面，仍需后续 F3-05/部署验收补证，不能把 Fake 模型测试等同于生产模型验收。
