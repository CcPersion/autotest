# F1-03 独立复审报告：项目与模块管理

## 1. 结论

- 功能结论：**PASS**。
- 审批结论：**APPROVE**。
- 后续门禁：**允许进入 F1-04**。
- 问题分级：Critical 0 项，Important 0 项，Minor 0 项。

本结论只批准 F1-03 的项目与模块目录能力，不代表环境配置、接口资产、Runner/JMeter 或 F1-04 已完成。

## 2. 审查依据与范围

依据 `docs/requirements/01-产品需求规格说明书.md` 6.1、REST 公共约束，`docs/tasks/01-开发任务清单.md` F1-03，以及 `F1-03-brief.md`、`F1-03-report.md`。静态复核了项目/模块 Controller、Service、JdbcTemplate Repository、统一异常映射、真实 PostgreSQL 集成测试，前端项目 API/切换器/管理弹层/共享模块树及真实浏览器 E2E 脚本；仓库非 Git，以当前文件和本轮新鲜证据为准。

## 3. 分级问题

### Critical

无。

### Important

无。

复审中发现并已关闭两项 Important：

1. 共享模块树原先允许新增/改名请求在首个请求完成前再次提交。现已分别增加 `creating`、`renaming` 入口 guard、按钮禁用和 `finally` 恢复；并发触发两次 submit 只调用 API 一次。
2. 同父节点向下拖到目标之后时，原先按移动前序号提交会偏后一位。现已根据源/目标是否同父及源序号方向换算移除后的最终 `position`；`A → B` 与 `C → A` 均提交 `position=1`。

### Minor

无。

## 4. 合同与行为核对

- 项目 REST 覆盖列表、新建、详情、编辑、归档和恢复；模块 REST 覆盖树、新建、改名、移动/排序和带 revision 的软删除。所有写请求继续受 Session 与 CSRF 保护。
- 复用 V1 `projects/modules` 表，没有新增迁移；项目和模块均不物理删除。项目归档/恢复、编辑及模块改名/移动/删除使用 revision CAS，陈旧版本返回统一 `409 REVISION_CONFLICT`。
- 模块写事务先锁所属项目；活动同级顺序会归一并持久化。移动目标只能是同项目活动模块，禁止自身/后代循环；跨项目路径 ID 或父 ID 统一返回 `404 RESOURCE_NOT_FOUND`。
- 活动项目名、同父活动模块名大小写不敏感唯一；冲突统一返回 `409 NAME_CONFLICT`。归档项目拒绝模块写入并返回 `409 PROJECT_ARCHIVED`。
- 非空模块不会级联或静默删除：存在活动子模块或活动接口引用时返回 `409 MODULE_NOT_EMPTY`，`details` 含 `childCount/apiCount`；空模块只更新 `archived_at`。
- 领域错误继续输出 `{code,message,details,traceId}`，数据库冲突不会直接泄露为 500。
- 前端顶部项目选择器支持真实加载、切换和无项目引导；项目管理支持新建、编辑、归档、查看归档及恢复，归档当前项目后刷新并选择下一个活动项目。
- 接口管理与接口用例共用真实递归模块树；支持根/子模块、改名、拖拽移动/排序、删除确认，并按 `MODULE_NOT_EMPTY`、`MODULE_CYCLE`、`NAME_CONFLICT`、`REVISION_CONFLICT` 显示明确提示。
- 未发现新表、JPA、RBAC、物理删除、版本中心、批量复杂操作或 F1-04 功能越界；其他页面主体仍明确保持原型数据。

## 5. 验证证据

复审者在两项前端修复后独立执行：

```powershell
npm.cmd --prefix web test -- --run src/components/ModuleTree.test.ts
```

结果：退出码 0，1 个文件、8 项测试全部通过；覆盖新增/改名不可重入、递归树、根/子模块创建、拖拽、同父上下方向 after 位置、非空删除和错误提示优先级。

采用主控同一实现轮次的新鲜证据：

- 项目/模块真实 PostgreSQL 定向测试：退出码 0。
- 真实浏览器 E2E：1/1 通过，2.5 秒；隔离 PostgreSQL、API/Vite 端口和进程无本次运行残留。
- 最终 `scripts/verify-local.ps1`：退出码 0；Maven 34 项、前端 32 项、typecheck、production build 和 Compose config 全部通过。

按聚焦复审要求，本轮未重复执行总门禁或真实 E2E。

## 6. 已知边界

- 当前只提供单实例下的项目内事务串行和 revision 乐观锁，不宣称分布式锁能力。
- 模块树是真实数据库数据；接口列表、编辑器、环境和后续执行能力仍不属于 F1-03。
- 本阶段不提供项目物理删除、模块级联删除或批量树操作。

## 7. 最终判定

F1-03 的项目生命周期、模块树持久化、revision CAS、跨项目隔离、循环与同名防护、非空删除提示和前端真实交互均有对应实现及本轮证据。复审发现的两个前端交互缺陷已经关闭，当前无 Critical/Important 遗留，最终结论为 **PASS / APPROVE**，**允许进入 F1-04**。
