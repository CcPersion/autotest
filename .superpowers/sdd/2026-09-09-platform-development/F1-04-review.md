# F1-04 独立复审报告

> 复审日期：2026-09-11  
> 复审模型：Sol-high  
> 范围：F1-04 环境、类型变量与密钥最小能力；不审查 F1-05，不修改生产代码。

## 1. 最终结论

- 质量结论：**PASS**
- 审批结论：**APPROVE**
- 后续门禁：**允许进入 F1-05**
- 遗留阻断：无 Critical、无 Important。

实现满足任务 F1-04 和实施简报的最小合同：项目内环境 CRUD/归档恢复、JSON 类型保持、严格 `${secret:name}` 引用、revision CAS、只写不回显的密钥管理、AES-256-GCM 加密和真实浏览器安全哨兵闭环。未引入新表以外的数据扩张、RBAC、Runner 密钥注入或 F1-05 资产能力。

## 2. 分级发现

### Critical

无。

### Important

无未关闭项。

首轮复审发现并已关闭以下 Important：

1. 环境页切换项目时曾保留旧项目数据、密钥表单明文，且旧列表慢响应可能覆盖新项目状态。修复后切换会同步清空环境、密钥、选择、错误、两类表单、`secretValue` 和 `saving`；列表请求使用 generation 隔离。
2. 上述修复最初只保护列表请求，旧项目 create/update/archive/restore 响应仍可能污染新项目。最终所有环境和密钥异步写操作均捕获 `projectId + generation`，旧响应不得写数据、错误、表单、明文或 `saving`，也不得 `emit('changed')`；慢环境创建和慢密钥创建跨项目回归已覆盖。
3. E2E 浏览器响应哨兵最初只扫描 `/secrets`。修复后全部 `/api/v1/**` 响应都检查随机明文哨兵，密钥端点另行验证不含 `value/ciphertext/nonce` 且掩码固定。

### Minor

1. `EnvironmentView` 页面文案“敏感信息仅在 Runner 内临时注入”描述的是后续完整运行链；F1-04 实际明确未实现 Runner 注入。建议后续改为将来时或“本阶段仅保存引用”，避免用户误认为已可执行；不影响本阶段合同与审批。

## 3. 关键合同核对

- **项目隔离与归档**：环境、密钥仓储的查询和更新均带 `project_id + resource id`；跨项目资源表现为统一 404。项目行加锁后检查归档状态，归档项目写入返回 `PROJECT_ARCHIVED`。
- **revision**：环境编辑/归档/恢复、密钥替换/归档均以预期 revision 做 CAS，成功 `revision + 1`，陈旧请求映射为 `409 REVISION_CONFLICT`。
- **JSON 与引用**：环境 variables 必须是 JSON object，使用 PostgreSQL JSONB 和 Jackson `JsonNode` 保持 number/boolean/null/object/array；嵌套对象和数组递归校验。密钥名和引用采用同一字符规则，未闭合、空白或非法引用拒绝，引用只允许命中当前项目活动密钥且原样保存、不展开。
- **加密与启动失败关闭**：`AUTOTEST_MASTER_KEY` 无默认可用值，Base64 解码后必须恰好 32 字节；缺失、非法 Base64、31/33 字节均失败启动且不回显输入。加密固定 `AES/GCM/NoPadding`、128 位 tag、每次随机 12 字节 nonce，AAD 为 `autotest-secret:v1:{projectId}:{secretId}`。
- **不泄密**：V2 `secrets` 仅保存 ciphertext、nonce 和元数据；API DTO 仅返回固定八位掩码，不暴露 value/ciphertext/nonce，也无明文 GET/恢复/物理删除接口。异常处理不返回底层加密细节。
- **前端**：当前项目驱动环境/密钥加载，环境变更后刷新壳层活动环境；JSON 编辑不把类型强制转成字符串；密钥提交防重复，关闭、提交及项目切换都会清空明文；跨项目异步响应已失效隔离。
- **E2E 与脚本**：真实 PostgreSQL、真实 Platform API、Vite 和 Chromium 串联；随机密钥值贯穿创建、引用、刷新、替换、归档/恢复。数据库检查密文字节及环境 JSON，API/Web 日志扫描哨兵，浏览器扫描全部 API 响应。通用脚本保留原始测试错误并汇总清理错误；进程、端口或容器清理失败均导致非零退出。
- **范围**：仅新增 V2 `secrets` 表；未实现代理、证书、JDBC、Redis、文件、Runner 注入、JMX、RBAC、批量接口、复杂版本中心或 F1-05。

## 4. 验证证据

本次复审避免重复总门禁和真实 E2E，采用静态独立核对、最小定向测试及主控修复后新鲜证据：

- 独立执行：`npm.cmd --prefix web test -- --run src/views/EnvironmentView.test.ts src/views/PlatformShellView.test.ts`，**2 个文件、12 项通过，退出码 0**。
- 静态核对：V2 迁移、Environment/Secret Controller-Service-Repository、`SecretCryptoService`、统一异常映射、环境/密钥前端 API 与页面、`environment-secret.spec.ts`、`Test-F1-04E2E.ps1`、通用 E2E/一键门禁脚本。
- 复用主控本轮新鲜证据：后端真实 PostgreSQL 定向测试 2 项通过；最终真实 F1-04 E2E 1 项通过；全部 API 响应、PostgreSQL、API/Web 日志哨兵均为 0；随机容器、进程树和端口清理通过；Maven 36 项、前端 47 项、typecheck、production build、Compose 与一键门禁均通过。

## 5. 审批边界

本审批只证明 F1-04 最小管理与安全存储能力完成，不代表 Runner 解密注入、运行时变量解析、JMX 临时密钥文件、代理/证书/数据源或生产 KMS/密钥轮换已经完成。F1-05 可开始，但不得借此宣称上述后续能力已交付。
