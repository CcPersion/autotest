# F1-04 实施简报：环境、变量与密钥最小能力

## 1. 目标与边界

本阶段让登录用户在项目内管理环境、类型化变量和 `${secret:name}` 引用，并以不可逆暴露的 API 方式管理密钥。环境复用 V1 `environments` 表；只新增最小 `secrets` 表迁移。

明确不做：代理、证书、JDBC、Redis、文件、Runner 注入、变量解析执行、JMX、项目白名单及 F1-05。继续只有一种登录用户，不增加 RBAC、批量接口或版本中心。

## 2. 数据与加密合同

新增 `V2__create_secrets.sql`，字段仅为：`id UUID`、`project_id UUID`、`name VARCHAR(128)`、`ciphertext BYTEA`、`nonce BYTEA`、`revision INTEGER`、`archived_at`、`created_by/updated_by`、`created_at/updated_at`；外键复用 `projects/users`，活动密钥名在项目内大小写不敏感唯一。不得修改已执行的 V1。

- 算法固定 `AES/GCM/NoPadding`；主密钥解码后必须恰好 32 字节（AES-256）。
- 主密钥只读 `AUTOTEST_MASTER_KEY` 的 Base64 值；无默认值，不写配置文件、数据库、响应或日志。
- 每次创建/替换生成新的 12 字节安全随机 nonce；GCM 认证标签随 ciphertext 保存，不增加第三个秘密列。
- AAD 固定为 UTF-8 `autotest-secret:v1:{projectId}:{secretId}`，防止密文跨项目或跨记录替换。
- 应用启动时环境变量缺失、Base64 非法或解码长度不是 32 字节必须失败关闭，错误只说明配置无效，不回显原值。
- 数据库只允许出现 ciphertext、nonce 和元数据；API 不提供解密读取接口。解密能力仅封装在平台内部服务中，本阶段不接 Runner。

## 3. REST 与 DTO

所有路径以 `/api/v1` 开头，沿用 Session、CSRF 和 `{code,message,details,traceId}`。

### 3.1 环境

| 方法与路径 | 请求 | 响应 |
| --- | --- | --- |
| `GET /projects/{projectId}/environments?includeArchived=false` | 无 | `EnvironmentResponse[]` |
| `POST /projects/{projectId}/environments` | `EnvironmentWrite` | 201 + `EnvironmentResponse` |
| `GET /projects/{projectId}/environments/{environmentId}` | 无 | `EnvironmentResponse` |
| `PUT /projects/{projectId}/environments/{environmentId}` | `EnvironmentWrite + revision` | 更新后的响应 |
| `POST /projects/{projectId}/environments/{environmentId}/archive` | `{revision}` | 归档后的响应 |
| `POST /projects/{projectId}/environments/{environmentId}/restore` | `{revision}` | 恢复后的响应 |

`EnvironmentWrite={name,baseUrl,variables}`。`name` 去首尾空白后必填；活动环境名在项目内大小写不敏感唯一。`baseUrl` 必须是无 userInfo/fragment 的绝对 HTTP(S) URL。

`variables` 必须是 JSON object；值可为字符串、数字、布尔、null、对象或数组，保存与返回时保持 JSON 类型。字符串内可使用 `${secret:name}`；保存时引用必须命中当前项目的活动密钥，API 只保留引用文本，绝不展开密钥值。

`EnvironmentResponse={id,projectId,name,baseUrl,variables,revision,archived,createdAt,updatedAt}`。

### 3.2 密钥

| 方法与路径 | 请求 | 响应 |
| --- | --- | --- |
| `GET /projects/{projectId}/secrets?includeArchived=false` | 无 | `SecretResponse[]` |
| `POST /projects/{projectId}/secrets` | `{name,value}` | 201 + `SecretResponse` |
| `PUT /projects/{projectId}/secrets/{secretId}` | `{value,revision}` | 替换后的响应 |
| `POST /projects/{projectId}/secrets/{secretId}/archive` | `{revision}` | 归档后的响应 |

`SecretResponse={id,projectId,name,mask,revision,archived,createdAt,updatedAt}`，`mask` 固定为 `••••••••`。创建、列表、替换、归档及错误响应均不得返回 `value`、ciphertext、nonce 或可推断长度的掩码；不提供明文 GET、恢复或物理删除。

## 4. 一致性、安全与错误码

- 所有仓储查询和更新同时限定 `project_id` 与资源 ID；跨项目 ID 与不存在资源统一 `404 RESOURCE_NOT_FOUND`。
- 环境编辑/归档/恢复、密钥替换/归档使用 `UPDATE ... WHERE revision=?` 并 `revision+1`；陈旧请求返回 `409 REVISION_CONFLICT`，`details` 仅含请求/当前 revision。
- 项目归档后禁止环境和密钥写入，返回 `409 PROJECT_ARCHIVED`。恢复环境若与活动环境同名，或创建同名活动资源，返回 `409 NAME_CONFLICT`。
- 空名称、非法 URL、非 object variables、空密钥值或非法引用返回 `400 VALIDATION_FAILED`；找不到活动引用返回 `400 SECRET_REFERENCE_NOT_FOUND`。
- 加解密认证失败统一 `500 SECRET_CRYPTO_ERROR`，不得区分 tag、nonce、AAD 或 key 错误细节。
- Controller、异常处理器和日志禁止记录请求体、主密钥、密钥值、ciphertext、nonce；统一脱敏器至少遮蔽 `password/secret/token/apiKey/Authorization/Cookie` 及已知测试哨兵值。

## 5. 实现文件与前端

- 后端新增 `environment`、`secret` 下的 Controller/Service/JdbcTemplate Repository/DTO，加密与脱敏放在各自单一职责组件；修改统一异常映射、配置和 Compose 示例变量。除 Flyway V2 外不新增表或生产依赖。
- `EnvironmentView.vue` 接入真实当前项目：无项目时显示创建项目引导；有项目时显示环境列表、创建/编辑、归档/恢复、类型化变量编辑和密钥引用选择。
- 密钥区只提供名称、固定掩码、创建、替换和归档；替换表单离开后清空明文。409 显示刷新重试，密钥引用无效显示具体字段但不显示秘密。
- 沿用 `PlatformShellView` 的项目上下文和现有视觉结构；环境切换器只显示活动环境，不重做导航或其他原型页面。

## 6. TDD 与验收

1. 真实 PostgreSQL：环境 CRUD/归档恢复、JSON 类型保持、同名冲突、revision CAS、归档项目拒写、所有跨项目路径 404。
2. 加密：固定 32 字节测试主密钥；创建/替换 nonce 不复用；数据库哨兵明文计数为 0；正确 AAD 可解密，替换 projectId/secretId 后认证失败；API 全响应不含明文、ciphertext、nonce。
3. 启动负向：缺失、非法 Base64、31/33 字节主密钥均启动失败；日志和异常不出现输入值。
4. 前端组件：项目/环境空状态、活动环境切换、变量类型保持、密钥固定掩码、替换后清空输入、归档恢复、revision/引用错误提示。
5. 真实浏览器 E2E：登录→创建密钥→创建含 `${secret:name}` 的环境→刷新仍存在→替换密钥→归档/恢复环境；随后扫描 PostgreSQL、API/Web 日志和浏览器响应，测试哨兵明文出现次数必须为 0。
6. E2E 使用隔离 PostgreSQL、真实 API/Vite/Chromium，随机容器、端口和进程必须清理；测试或清理任一失败都非零退出。

```powershell
wsl.exe -d Ubuntu -- bash -lc "cd /mnt/d/codexWorkSpec/autotest && mvn -pl platform-api -am -Dtest='*Environment*Test,*Secret*Test' -Dsurefire.failIfNoSpecifiedTests=false test"
npm.cmd --prefix web test -- --run
npm.cmd --prefix web run typecheck
npm.cmd --prefix web run build
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Test-F1-04E2E.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-local.ps1
```

只有真实 PostgreSQL、密文/AAD/日志哨兵、前端组件、真实浏览器 E2E 和总门禁均有本轮新鲜证据，并经 Sol 复审通过，才允许进入 F1-05。
