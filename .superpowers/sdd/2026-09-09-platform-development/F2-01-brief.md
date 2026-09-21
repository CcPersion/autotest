# F2-01 实施简报：完整 HTTP 输入、文件资产与安全请求执行

## 1. 任务边界

本任务依赖 F1-10，目标是补齐单次接口用例执行所需的 HTTP 输入形态和受控文件/证书能力：文本、URL Encoded、JSON、Multipart（文本与单/多文件）、单次运行内 Cookie 会话、重定向、超时、HTTP 代理、PKCS12 客户端证书和最终请求预览。

本任务不实现变量函数、完整提取/断言、数据行、场景编排、SQL/Redis、SSE、附件归档、PAC/SOCKS/NTLM/Kerberos、PEM/JKS、跨运行 Cookie、性能压测、多 Runner 或 RBAC。已有 F1-09/F1-10 能力必须保持兼容。

## 2. 不可变文件资产

新增项目隔离的 `file_assets` 元数据和私有对象存储（MinIO）。文件内容不可原地替换；内容变化必须创建新的 `fileId`，旧资产只能归档。

最小元数据：

| 字段 | 约束 |
|---|---|
| `id` | UUID，服务端生成，作为唯一 `fileId` |
| `project_id` | 与项目绑定，所有读写必须校验项目归属 |
| `kind` | `REQUEST_FILE` 或 `PKCS12` |
| `original_name` | 仅作为展示名/线上 multipart filename；只允许安全 basename，拒绝控制字符、路径分隔符、盘符、UNC 和 `..` |
| `mime_type` | 服务端允许列表校验 |
| `size` | 服务端实际字节数，有限正值 |
| `sha256` | 服务端重新计算并保存 |
| `object_key` | 服务端随机生成的内部键；API、日志、JMX 和浏览器不得返回 |
| `status` | `UPLOADING`/`ACTIVE`/`ARCHIVED`；`UPLOADING` 仅为服务端恢复状态，不能被用例选择 |
| `revision` | 乐观锁，归档/删除必须 CAS |

对象桶保持私有。浏览器和 Runner 不获得 MinIO 凭据或通用预签名 URL。上传使用可恢复状态机：先以随机 `uploadId` 写入 `UPLOADING` 元数据并把流写入随机临时对象，写入期间按单文件上限限流并计数；服务端完成实际大小、精确 MIME、SHA-256、文件名校验后，在数据库行锁内原子预留项目配额，再将对象复制/改名为新的随机最终对象键，最后在同一数据库事务中写入 `ACTIVE` 元数据并清除预留。任一步失败都释放预留并删除临时/未引用最终对象；数据库提交失败时最终对象只进入带 TTL 的孤儿清理队列，绝不对 API 可见。服务重启恢复任务会幂等清理超时 `UPLOADING`、临时对象和无 `ACTIVE` 引用的最终对象，不能删除仍被运行快照引用的对象。

归档检查所有持久化引用：接口定义 requestSpec、接口用例 body、环境 requestDefaults/clientCertificate，以及已发布场景引用；仍被这些活动资产引用时返回 `FILE_IN_USE`。仅剩运行快照引用时允许归档，归档不删除对象，历史运行仍按快照读取。客户端未持久化 draft 无法参加归档检查，preview/debug 在构建计划时重新检查文件必须为 `ACTIVE`。物理删除/GC 必须同时满足 `status=ARCHIVED`、无活动资产引用、无运行快照引用且超过保留期；所有归档/删除操作使用 `revision` CAS。这样“归档后快照仍可运行”和“引用冲突”分别适用于运行快照与活动资产引用，不再矛盾。

## 3. 请求、预览和运行公共契约

### 3.1 Body 结构

接口用例的 Body 类型为 `NONE`、`TEXT`、`JSON`、`URLENCODED`、`MULTIPART`。URL Encoded 和 Multipart 必须是结构化数组，不接受 JSON 文本伪装：

- URL Encoded：每项含 `name`、`value`、`enabled`；保留空值、重复键和顺序。
- Multipart：每项含 `name`、`kind=TEXT|FILE`、`value`（TEXT 时）或 `fileId`（FILE 时）、`contentType`、`enabled`；文件项可多个，文本与文件可混合。
- 客户端不得提交本地 `path`、MinIO `objectKey`、容器路径或任意脚本。

PKCS12 客户端证书只接受 `{fileId, passwordSecretRef}`。证书密码必须引用现有加密密钥，不能出现在接口定义、执行计划、JMX、日志、报告或模型上下文中。

### 3.2 权威计划、预览与调试发送

普通运行 API 只接受已保存目标（接口用例、场景或集合）、`environmentId` 和幂等键，不接受客户端完整 `executionPlan`，也不能用接口定义 ID 冒充 `API_CASE`。

接口编辑器“发送”使用独立 `debug-runs` 服务端入口：输入 `projectId`、`environmentId`、`definitionId` 或经校验的草稿内容、`idempotencyKey`。服务端执行与正式运行共用同一个 `PlanBuilder`、变量合并、目标白名单和 Body/文件校验流程。

预览 API 使用同一 `PlanBuilder`，只返回脱敏后的最终 method、URL、Query、Header、Cookie、Body 摘要、文件展示名/大小/SHA-256、代理和证书元数据；不返回 secret、对象键、临时路径、证书密码或 MinIO 凭据。页面不能在浏览器自行拼接另一份执行计划。

运行创建时把所需文件快照写入运行计划：`fileId`、`size`、`sha256`、`mimeType`、`wireFileName`。之后文件归档不影响该运行；Runner 只能按 `runId/projectId/fileId` 绑定下载，不重新按当前 `ACTIVE` 状态解析资产。

### 3.3 F2-01 公共 API 与错误码

以下接口是本任务冻结的最小公共契约；所有接口均位于 `/api/v1`，响应错误统一为 `{code,message,details,traceId}`，不回显本地路径、对象键、密钥值或完整目标 URL。

| 方法与路径 | 请求 DTO（互斥字段已标明） | 成功 | 主要错误 |
|---|---|---|---|
| `POST /projects/{projectId}/files` | `multipart/form-data`：`file`、`kind=REQUEST_FILE|PKCS12`；文件名取 multipart filename，不接受 `path/objectKey` | `201 FileAssetView`（不含 objectKey） | `400 FILE_NAME_INVALID/FILE_EMPTY/MIME_UNSUPPORTED/FILE_LIMIT_EXCEEDED/QUOTA_EXCEEDED`、`409 QUOTA_RESERVATION_CONFLICT` |
| `GET /projects/{projectId}/files?status=ACTIVE|ARCHIVED` | 无 | `200 FileAssetView[]` | `400 STATUS_INVALID` |
| `POST /projects/{projectId}/files/{fileId}/archive` | `{revision}` | `200 FileAssetView(status=ARCHIVED)` | `404 FILE_NOT_FOUND/PROJECT_MISMATCH`、`409 FILE_IN_USE/REVISION_CONFLICT` |
| `POST /projects/{projectId}/preview` | `PreviewTarget` 三选一：`SavedDefinitionTarget{definitionId,environmentId}`、`DraftDefinitionTarget{definitionId,draft,environmentId}`、`SavedExecutableTarget{targetType=API_CASE|SCENARIO|SUITE,targetId,environmentId}`；不允许 `executionPlan` | `200 PreviewView` | `400 TARGET_INVALID/PLAN_TAMPERED/...`、`404 TARGET_NOT_FOUND`、`422 URL_NOT_ALLOWED/FILE_NOT_ACTIVE/...` |
| `POST /projects/{projectId}/debug-runs` | `DebugTarget` 二选一：`SavedDefinitionTarget` 或 `DraftDefinitionTarget`，另含 `idempotencyKey`（JSON Schema `oneOf`） | `202 RunView` | `400 DEBUG_TARGET_INVALID/IDEMPOTENCY_INVALID`、`409 IDEMPOTENCY_CONFLICT`、`422` 同 PlanBuilder 错误 |
| `POST /projects/{projectId}/runs` | `SavedExecutableTarget{targetType=API_CASE|SCENARIO|SUITE,targetId,environmentId}` + `idempotencyKey`；只接受已保存可执行目标，不接受 `executionPlan` | `202 RunView` | `400 TARGET_TYPE_INVALID/IDEMPOTENCY_INVALID`、`404 TARGET_NOT_FOUND`、`409 IDEMPOTENCY_CONFLICT` |
| `GET /internal/runs/{runId}/files/{fileId}` | Runner 内部认证；项目绑定由服务端从运行快照取得 | `200 application/octet-stream` 流 | `401/403`、`404 FILE_SNAPSHOT_NOT_FOUND`、`409 SNAPSHOT_METADATA_MISMATCH` |
| `GET /internal/runs/{runId}/secrets/{secretRef}` | Runner 内部 mTLS/服务认证，仅允许该运行计划中的引用 | `200` 一次性内存响应（不落库、不写日志） | `401/403`、`404 SECRET_REF_NOT_FOUND`、`409 SECRET_NOT_IN_SNAPSHOT` |

`FileAssetView` 只返回 `fileId/projectId/kind/originalName/mimeType/size/sha256/status/revision`。`PreviewView` 只返回脱敏请求摘要和文件展示元数据。`SavedDefinitionTarget` 仅代表接口定义，不是可执行的 `API_CASE`；只有 `SavedExecutableTarget` 可进入正式 runs。统一错误码还包括 `BODY_SCHEMA_INVALID`、`DUPLICATE_FILE_REFERENCE`、`DUPLICATE_WIRE_FILE_NAME`、`SECRET_REF_INVALID`、`PROXY_NOT_ALLOWED`、`REDIRECT_NOT_ALLOWED`、`TIMEOUT_INVALID`、`DNS_NOT_ALLOWED`、`DOWNLOAD_PATH_INVALID`、`DOWNLOAD_CHECKSUM_MISMATCH`。旧的 `path`、`objectKey`、客户端 `executionPlan` 字段一律 `400 PLAN_TAMPERED`，不得静默忽略。

URL Encoded 允许重复 `name`，按数组顺序发送，空值也发送；Multipart 允许重复字段名但同一请求的线上 `wireFileName` 不得重复，重复文件引用和重复线上文件名分别返回上述错误。TEXT/JSON/表单字段统一按 UTF-8 编码为字节后计算大小和 SHA-256。文件名最多 128 个 Unicode 码点且不超过 255 个 UTF-8 字节；空文件拒绝。请求文件允许且仅允许 `text/plain`、`text/csv`、`application/json`、`application/xml`、`application/octet-stream`、`application/pdf`、`image/png`、`image/jpeg`；PKCS12 必须同时满足 MIME=`application/x-pkcs12` 且扩展名为 `.p12` 或 `.pfx`。

项目配额使用数据库 `file_quota(project_id,committed_bytes,reserved_bytes,revision)` 行锁：临时对象完成校验后，在最终对象提交前原子检查 `committed+reserved+size <= 100 MiB` 并增加 `reserved_bytes`，对象校验/提交失败释放预留，`ACTIVE` 提交时转为 `committed_bytes`；归档不减少 `committed_bytes`，因为对象仍被保留；只有 GC 在确认无活动资产引用、无运行快照引用且对象物理删除成功后，才在同一事务中扣减 `committed_bytes`。并发上传不得超配额。

### 3.4 HTTP 输入 Schema 与继承

接口定义和接口用例共用以下结构化 `HttpInputSpec`（实现为 `additionalProperties:false` 的 JSON Schema，未列出的字段一律拒绝）：

```json
{
  "pathParams": {"orderId": "${id}"},
  "query": [{"name": "q", "value": "a&b", "enabled": true}],
  "headers": [{"name": "Accept", "value": "application/json", "enabled": true}],
  "cookies": [{"name": "sid", "value": "${secret:session}", "enabled": true}],
  "body": {"type": "NONE|TEXT|JSON|URLENCODED|MULTIPART", "value": "..."},
  "options": {
    "followRedirects": true,
    "connectTimeoutMillis": 30000,
    "readTimeoutMillis": 30000,
    "totalTimeoutMillis": 30000,
    "proxy": {"scheme": "http", "host": "proxy.internal", "port": 8080, "username": "svc", "passwordSecretRef": "proxy-pass"},
    "clientCertificate": {"fileId": "uuid", "passwordSecretRef": "p12-pass"}
  }
}
```

`body.type` 为 `NONE` 时不得有 `value`；`TEXT`/`JSON` 的 `value` 是 UTF-8 字符串；`URLENCODED` 的 `value` 是带 `name/value/enabled` 的有序数组；`MULTIPART` 的 `value` 是带 `name/kind=TEXT|FILE/value|fileId/contentType/enabled` 的有序数组。FILE 行只能有 `fileId`，不能有 `path/objectKey`。`options` 中三个超时均为整数 `1..120000`，`followRedirects` 为布尔；proxy 只允许 `scheme=http`，密码只能是 `passwordSecretRef`；clientCertificate 只能是上文 PKCS12 结构。

环境的 `requestDefaults` 只允许 `headers`、`cookies`、`options`；接口定义保存完整 `HttpInputSpec`；接口用例只保存较窄的 `HttpCaseOverrideSpec`：可选 `pathParams/query/headers/cookies` 值覆盖、同类型 `body.value` 覆盖和 `connectTimeoutMillis/readTimeoutMillis/totalTimeoutMillis`，禁止 `method/url/body.type/proxy/clientCertificate/followRedirects`。合并顺序固定为“环境默认 → 接口定义 → 接口用例”：数组按名称匹配并保留定义顺序，未声明名称不可新增；参数值和三个超时按用例 > 定义 > 环境；`proxy`、`clientCertificate` 和 `followRedirects` 按定义 > 环境。Body 类型只能由接口定义决定，用例只能覆盖同类型的 `value`，不能把 `NONE` 改成文件或反向改变类型。`fileId`、证书引用和代理端点都在 PlanBuilder 阶段按项目/环境归属重新校验并写入运行快照。

`preview` 接受上表三种 `PreviewTarget` `oneOf`；`debug-runs` 接受 `SavedDefinitionTarget` 或 `DraftDefinitionTarget` 二选一，二者都必须带 `environmentId` 和幂等键；已保存接口定义调试时使用 `SavedDefinitionTarget`，不能把 `definitionId` 伪装成 `API_CASE`。draft 只在本次请求内存在，不能成为持久引用。preview/debug/正式 runs 都必须调用同一个服务端 `PlanBuilder`，因此未保存编辑器草稿也能预览/发送，但不能绕过活动文件、密钥、目标白名单和超时校验。正式 `runs` 只接受 `SavedExecutableTarget`。

## 4. Runner 文件与证书生命周期

Runner 通过内部认证、绑定 `runId/projectId/fileId` 的下载接口流式获取对象，写入每次运行随机目录。下载后必须校验项目、快照元数据、大小、SHA-256、路径 containment、`NOFOLLOW_LINKS` 和非符号链接；校验失败不得执行请求。

JMX 可引用 Runner 随机运行目录内的临时文件路径，这是执行所需的内部路径；严禁出现客户端路径、对象键、MinIO 凭据和证书密码。线上 multipart filename 只能使用校验后的 `originalName`，不能决定对象键或运行目录。

PKCS12 计划只携带 `passwordSecretRef`。Runner 用 `runId` 绑定的 secrets 接口解析引用，明文密码只写入权限 `0600` 的临时 JMeter properties 文件并通过受控属性名读取；不得出现在 JMX、命令行、环境变量、日志、报告或模型上下文。证书与密码文件在 JMeter 启动前校验存在且 containment 正确，任何失败均不启动请求。

请求成功、失败、取消、Runner 中断和启动恢复都必须 finally 清理请求文件、证书文件、密码属性文件和临时目录。日志与报告只记录 `fileId`、展示名和脱敏元数据。

## 5. HTTP 语义和安全策略

- URL 仅允许 HTTP/HTTPS；拒绝 userinfo、fragment、控制字符、非法 IDN 和不完整主机名。
- 目标地址和每一次重定向的最终地址都要按项目目标白名单逐跳校验；越权跳转不得触达。
- HTTP 代理端点单独按项目白名单校验，代理不能绕过最终目标策略；本任务只支持显式 HTTP 代理。
- Cookie 允许字面量、`${name}` 和 `${secret:name}` 三种形式；线上请求发送解析后的真实值，只有预览、日志和报告按 secret 引用、敏感键集合及已知敏感值统一遮蔽，不能把线上 Cookie 改成 `***`。Cookie 只在单次运行和其重定向链内共享，不跨运行持久化。
- 超时有限且必须为正值，优先级固定为：用例覆盖 > 接口定义 > 环境默认；`0` 不表示无限等待，非法或超上限值在保存/运行前拒绝。
- 目标域名解析和连接策略必须可注入测试；每一跳由 Runner 的 `ApprovedDnsResolver` 解析全部地址并按项目白名单/CIDR 校验，HTTP 客户端必须把获准地址固定到实际 socket，同时保留原 Host 和 HTTPS SNI，禁止后续默认 DNS 再解析。解析结果变化或发生 rebinding 时 fail-closed。
- 启用 HTTP 代理时，Runner 先用内部认证调用受控代理的 `authorize(runId,targetUrl,approvedCidrs)`；代理负责对每一跳解析、校验并固定目标地址，拒绝未授权目标，Runner 仍单独校验代理端点白名单。代理链不使用浏览器代理设置，也不能通过代理绕过目标策略。

目标白名单语法冻结为：精确 hostname（可带端口）、单层 `*.example.com` hostname、IPv4/IPv6 字面量和 CIDR；禁止裸 `*`、多层通配符、userinfo、fragment。URL 未显式端口时按 scheme 归一为 HTTP `80`、HTTPS `443`；带端口的白名单只匹配该端口；不带端口的 hostname/IP 白名单只授权归一后的默认端口，不授权其他端口。PlanBuilder 在创建运行时把规范化白名单和 `targetPolicySnapshot` 写入运行计划，后续资产修改不改变本次运行。判定规则为：IP 字面量必须命中同一地址/CIDR 及端口；hostname 必须命中精确/单层通配符及端口，且本次解析返回的每个 A/AAAA 地址都必须命中显式 CIDR/地址或同一白名单条目的已批准地址集合；混合解析只要有一个地址未获准就拒绝整次请求，不从中挑选“安全地址”。回环、链路本地和其他私网地址默认拒绝，只有显式 CIDR 才允许。每次重定向重新按同一快照解析并校验，地址集合变化即视为 rebinding 并拒绝。受控代理收到同一 `targetPolicySnapshot`，对目标执行相同规则；direct 与 proxy 两条链路都必须记录“未授权地址实际连接次数为 0”。

本任务默认重定向、代理和证书参数均由服务端校验后的执行计划提供；不开放脚本、PAC、动态代理认证或任意 Java 代码。

## 6. 配置边界（实现前冻结）

具体值写入共享配置并由 API/Runner 共用：

- 单文件最大字节数：`10 MiB`；单请求最多 `10` 个文件；项目累计配额：`100 MiB`。
- 允许 MIME：普通请求文件仅允许 `text/plain`、`text/csv`、`application/json`、`application/xml`、`application/octet-stream`、`application/pdf`、`image/png`、`image/jpeg`；PKCS12 仅允许 `application/x-pkcs12` 与 `.p12/.pfx` 展示名。
- 连接、读取和总请求超时均为有限正值，最大 `120s`；默认 `30s`。
- 文件名长度、空文件、重复文件引用、重复线上文件名、非法字符和超限值均有明确错误码；重复表单字段名按 3.3 规则保留，不作为错误。

实现不得把上述值散落在前端、Runner 和 Platform 中；前端只展示服务端返回的限制。

## 7. 页面范围

接口编辑页新增结构化 Body 类型选择器、URL Encoded 表格、Multipart 文本/文件行、Cookie 表格、超时/重定向/代理高级配置、项目文件/PKCS12 选择器和证书密码 secret 选择器。

请求预览与发送结果只展示服务端脱敏结果。删除 Cookie 只删除 Cookie 行，不得误删 Header；文件选择器不得让用户输入本地路径作为执行参数。页面校验与后端错误码保持一致。

## 8. 必须先写的失败测试

### Platform、PostgreSQL、MinIO

- 跨项目 `fileId`、不存在/归档文件、绝对路径、`../`、UNC、盘符、控制字符、超大小/数量/MIME、重复引用和 revision 冲突。
- 临时对象校验失败、最终对象完成后数据库提交失败的补偿与孤儿对象清理。
- 预览/运行拒绝客户端 `executionPlan`、本地 path、objectKey 和错误 target type；接口预览与实际计划使用同一构建结果。
- 文件上传/列表/归档、预览、debug-runs、正式 runs 和内部下载/secret API 的 OpenAPI 双向契约；`definitionId`/`draft` `oneOf`、未知字段、状态码和全部错误码均有正负向测试。
- 上传并发配额的行锁测试、服务重启后的 `UPLOADING`/孤儿清理幂等测试，以及“仅运行快照引用可归档、快照保留期内不可 GC”测试。

### Runner/JMeter

- 快照归档后仍可运行；checksum、size、项目绑定、符号链接和越界路径失败；成功/失败/取消/启动恢复后无临时文件。
- TEXT 精确字节、URLENCODED 特殊字符/空值/重复键、Multipart 文本+单文件+多文件并校验 filename/MIME/字节/SHA-256。
- Cookie 在重定向链内回送、follow on/off、越权跳转未触达、连接/读取/总超时分别失败。
- HTTP 代理真实经过；代理端点越权和目标越权均被拒绝；direct/proxy 两条链路均覆盖 hostname、IP、CIDR、混合解析结果、DNS rebinding，验证实际 socket 未触达未授权地址并记录为 0。
- 无证书 mTLS 失败、正确 PKCS12 成功，JMX/日志无密码和对象凭据。
- PKCS12 secret 仅通过 run-bound 内部接口获取，临时 properties 权限为 `0600`，异常/取消/重启后无证书或密码文件残留。

### Web 与安全

- 结构化 URL Encoded/Multipart 编辑、Cookie 删除回归、文件选择器拒绝 path 输入、预览与 mock 实际收到请求语义一致。
- 脱敏扫描覆盖浏览器响应、报告、日志、JMX、临时文件和对象存储元数据。
- OpenAPI 双向契约只接受同项目活动 `fileId`，禁止旧 path 字段。

## 9. 真实验收矩阵

隔离 WSL Compose 必须同时启动 PostgreSQL、MinIO、Platform、Web、Runner、固定 JMeter 5.6.3、独立回显服务、真实 HTTP 代理和 mTLS 服务。浏览器完成上传→选择文件/证书→服务端预览→发送→报告；回显服务校验每种 Body、Cookie、文件名、MIME、字节和 SHA-256；代理记录实际经过；direct/proxy 的 DNS rebinding 与未授权地址均有“零触达”证据；重定向和超时得到对应报告终态；无证书失败、正确 PKCS12 成功；最终扫描无 secret、证书密码、MinIO 凭据，容器、卷、对象和 Runner 临时文件精确清理。

## 10. 回滚与明确不做

数据库迁移必须可在空库和同库二次启动幂等执行；迁移失败按 Flyway 事务边界回滚新增表/索引，不触碰既有资产表数据。代码失败时禁止删除历史文件资产。对象存储失败通过补偿任务清理临时对象，不能回滚已存在的历史资产；恢复任务必须可重复执行且保留审计事件。

明确不做：F2-02 变量函数、F2-03 断言扩展、数据行/场景、JDBC/Redis、PAC/SOCKS/NTLM/Kerberos、PEM/JKS、跨独立运行持久 Cookie、响应附件归档、Mock 平台、多 Runner、性能压测和任意代码执行。

## 11. 进入实现门禁

本简报必须先由 Sol 高模型审查通过；通过后才允许 Luna 按本简报写失败测试、实现代码、执行真实 WSL 验收。实现后的测试和最终放行仍必须按“Luna 新鲜测试 → Sol 独立复审”执行。
