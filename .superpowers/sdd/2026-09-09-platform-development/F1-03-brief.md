# F1-03 实施简报：项目与模块管理

## 1. 目标与边界

本任务让登录用户建立测试资产目录：管理项目，并在项目内维护任意层级模块树。复用 V1 已有 `projects`、`modules` 表，不新增迁移，不实现 RBAC、物理删除、版本中心、批量接口、环境或 F1-04 之后的能力。

项目归档后默认不出现在项目切换器中，但可在项目管理中查看并恢复。模块“删除”统一写入 `archived_at`；非空模块拒绝删除，不级联处理子模块或接口。

## 2. REST 合同

所有接口位于 `/api/v1`，沿用 F1-02 Session、CSRF 和 `{code,message,details,traceId}` 错误结构。

### 2.1 项目

| 方法与路径 | 请求 | 结果 |
| --- | --- | --- |
| `GET /projects?includeArchived=false` | 无 | `ProjectSummary[]`，按更新时间倒序 |
| `POST /projects` | `{name,description?}` | 201 + `ProjectDetail` |
| `GET /projects/{projectId}` | 无 | `ProjectDetail` |
| `PUT /projects/{projectId}` | `{name,description?,revision}` | 更新并返回 `revision+1` |
| `POST /projects/{projectId}/archive` | `{revision}` | 归档并返回新版本 |
| `POST /projects/{projectId}/restore` | `{revision}` | 恢复并返回新版本 |

`ProjectSummary/ProjectDetail` 固定包含 `id,name,description,revision,archived,createdAt,updatedAt`。名称去首尾空白后必填；活动项目名称大小写不敏感唯一。归档、恢复和编辑均使用 `revision` 乐观锁，不物理删除项目。

### 2.2 模块

| 方法与路径 | 请求 | 结果 |
| --- | --- | --- |
| `GET /projects/{projectId}/modules/tree` | 无 | 当前项目活动模块树 `ModuleNode[]` |
| `POST /projects/{projectId}/modules` | `{name,parentId?,position?}` | 201 + 新节点 |
| `PUT /projects/{projectId}/modules/{moduleId}` | `{name,revision}` | 改名并返回新版本 |
| `POST /projects/{projectId}/modules/{moduleId}/move` | `{parentId?,position,revision}` | 移动/排序并返回新版本 |
| `DELETE /projects/{projectId}/modules/{moduleId}?revision={revision}` | 无 | 空模块软删除，204 |

`ModuleNode` 固定包含 `id,projectId,parentId,name,sortOrder,revision,children`。`position` 为目标同级列表的零基位置，省略时追加到末尾；服务端持久化后将相关同级 `sort_order` 归一为连续整数。

## 3. 一致性与错误规则

- 所有写操作先锁定所属项目行，在同一事务内校验和更新，避免同一项目的并发排序互相覆盖。
- 编辑、归档、恢复、改名、移动和删除使用 `UPDATE ... WHERE revision=?`；过期版本返回 409 `REVISION_CONFLICT`，不静默覆盖。
- 路径中的项目不存在，或模块/父模块不属于该项目时，统一返回 404 `RESOURCE_NOT_FOUND`，不得泄露其他项目资产。
- 归档项目禁止继续修改模块，返回 409 `PROJECT_ARCHIVED`；恢复后可继续使用。
- 模块父节点必须是同项目活动节点；不得把节点移动到自身或任一后代下，返回 409 `MODULE_CYCLE`。
- 活动同级模块名称大小写不敏感唯一；冲突返回 409 `NAME_CONFLICT`。
- 模块只在没有活动子模块且没有活动接口定义引用时允许软删除；否则返回 409 `MODULE_NOT_EMPTY`，`details` 给出 `childCount`、`apiCount`，由前端明确提示，不提供级联删除。
- 参数缺失、空名称、负数位置返回 400 `VALIDATION_FAILED`。

## 4. 后端实现范围

- 新增 `project`、`module` 领域下的 Controller、Service、`JdbcTemplate` Repository 和 DTO；不引入 JPA。
- 当前用户从 Spring Security `Authentication` 解析，再通过 `UserRepository` 获取用户 ID，写入 `created_by/updated_by`。
- 模块树由数据库平面结果在服务层组装，查询次数固定，不按节点逐级查询。
- 移动循环校验使用同项目递归查询或已加载树；排序更新与节点更新必须处于同一事务。
- 仅返回必要字段，不返回密码、数据库内部信息或其他项目数据。

## 5. 前端实现范围

- 顶部项目选择器改接真实 `/projects`；支持空状态、新建项目和切换当前项目。
- 提供轻量项目管理弹层：编辑、归档、查看已归档项目和恢复。归档当前项目后自动选择下一个活动项目；无项目时显示创建引导。
- 接口管理和接口用例页共用真实模块树组件；支持新建子模块、改名、拖拽移动/排序和删除确认。
- 非空删除时展示后端 `childCount/apiCount`，不伪装成功；409 版本冲突提示刷新后重试。
- 本任务只替换项目与模块相关的原型数据，不改其他页面视觉结构，不实现接口资产编辑。

## 6. TDD 与验收

1. 先写真实 PostgreSQL 集成红灯：项目新建/编辑/归档/恢复、名称冲突、revision 冲突、跨项目 ID 404。
2. 再写模块红灯：任意层级建树、同级排序持久化、跨父移动、禁止自环/后代环、跨项目父节点 404、非空删除 409、空模块软删除。
3. 前端先写组件测试：项目加载/切换/空状态、项目管理动作、模块树渲染和操作错误提示；再做最小实现。
4. 真实浏览器连接 PostgreSQL：登录后创建项目与三级模块，移动并刷新确认层级和顺序；验证非空删除被明确拒绝；归档/恢复项目后状态持久化。
5. 最后运行受影响模块测试、前端测试/typecheck/build、一次真实 E2E 和一次项目总门禁；Sol 复审通过后才进入 F1-04。

