# F1-05 实施简报：GET/POST JSON 接口定义与用例 API

## 1. 目标与边界

本阶段只让登录用户在项目内保存、查询、修改和归档 **GET/POST + JSON** 接口定义与接口用例，并形成可由后续 F1-07 编译的稳定结构；不做页面（F1-06）、发送/调试、Runner/JMeter/JMX 或运行计划。

明确不做：PUT/PATCH/DELETE/HEAD/OPTIONS、Cookie、表单/文本/文件 Body、数据行、提取、前置/清理、重试、场景、复制、批量接口、快照/版本中心、AI/OpenAPI 导入。只有一种登录用户，不增加 RBAC。

## 2. 数据与迁移决策

**不新增 V3，不修改 V1/V2。** V1 已包含满足本阶段的 `api_definitions`、`api_cases`、项目内复合外键、活动名称唯一索引、revision 与归档字段；F1-05 直接实现其 Repository/Service/API。若实现发现结构不满足合同，必须先停下经 Sol 复审，不得回改已执行迁移。

- `api_definitions`：`module_id/name/http_method/url_template/request_spec JSONB/revision/archived_at/audit`。
- `api_cases`：`api_definition_id/name/case_spec JSONB/variables_json JSONB/assertions_json JSONB/revision/archived_at/audit`。
- 不拆 path/query/header/assertion 子表；本阶段都随所属资源整体 CAS 更新。

`request_spec` 固定为：

```json
{"pathParams":[{"name":"orderId","value":"${orderId}"}],"query":[{"name":"verbose","value":"true","enabled":true}],"headers":[{"name":"X-Tenant","value":"${tenant}","enabled":true}],"body":{"type":"JSON","value":{"orderId":"${orderId}"}}}
```

`body` 仅允许 `{"type":"NONE"}` 或 `{"type":"JSON","value":<任意合法 JSON 值>}`；GET 必须为 NONE，POST 可为 NONE/JSON。数组顺序持久化；未知字段拒绝，不把对象或数组强制转为字符串。

`case_spec` 只保存覆盖值，不复制 method/url/module：

```json
{"pathParams":{"orderId":"1001"},"query":{"verbose":"false"},"headers":{"X-Tenant":"qa"},"body":{"type":"JSON","value":{"orderId":1001}}}
```

覆盖键必须已在所属定义声明；接口用例不得改变所属定义，`api_definition_id` 创建后不可更新。

`variables_json` 为 JSON object，值可为 string/number/boolean/null/object/array。`assertions_json` 为有序数组，仅允许：

```json
{"type":"STATUS","operator":"EQUALS","expected":200}
{"type":"JSON_PATH","expression":"$.data.id","operator":"EXISTS"}
{"type":"JSON_PATH","expression":"$.data.state","operator":"EQUALS","expected":"PAID"}
```

本阶段 JSONPath operator 只支持 `EXISTS/EQUALS`；其他断言、操作符和实际执行延后，后续可扩 enum 而不改变信封。

## 3. REST 与 DTO

统一前缀 `/api/v1`，响应沿用 `{code,message,details,traceId}` 错误结构。

| 资源 | 方法与路径 |
| --- | --- |
| 定义列表/创建 | `GET/POST /projects/{projectId}/api-definitions?moduleId=&includeArchived=false` |
| 定义详情/更新 | `GET/PUT /projects/{projectId}/api-definitions/{definitionId}` |
| 定义归档 | `POST /projects/{projectId}/api-definitions/{definitionId}/archive` |
| 用例列表/创建 | `GET/POST /projects/{projectId}/api-definitions/{definitionId}/cases?includeArchived=false` |
| 用例详情/更新 | `GET/PUT /projects/{projectId}/api-definitions/{definitionId}/cases/{caseId}` |
| 用例归档 | `POST /projects/{projectId}/api-definitions/{definitionId}/cases/{caseId}/archive` |

`DefinitionWrite={moduleId?,name,method,urlTemplate,requestSpec,revision?}`；创建忽略/拒绝 revision，更新必填。`DefinitionResponse={id,projectId,moduleId,name,method,urlTemplate,requestSpec,revision,archived,createdAt,updatedAt}`。

`CaseWrite={name,caseSpec,variables,assertions,revision?}`；所属定义只来自路径。`CaseResponse={id,projectId,apiDefinitionId,name,caseSpec,variables,assertions,revision,archived,createdAt,updatedAt}`。创建返回 201；更新/归档返回更新后资源；归档请求为 `{revision}`。本阶段无物理删除、恢复和批量端点。

## 4. 隔离、一致性与校验

- 所有查询、更新和复合外键都限定 `project_id`；definitionId、caseId、moduleId 任一跨项目、错配或不存在均统一 `404 RESOURCE_NOT_FOUND`，不泄露资源是否存在。
- 创建/移动定义只能引用当前项目活动模块；根模块用 `moduleId=null`。归档项目拒绝写入 `409 PROJECT_ARCHIVED`；归档定义拒绝新建/更新用例 `409 PARENT_ARCHIVED`。
- 定义更新/归档、用例更新/归档均执行 `UPDATE ... WHERE project_id=? AND id=? AND revision=?` 并 `revision+1`；陈旧请求返回 `409 REVISION_CONFLICT`，details 仅含 requested/current revision。
- 活动定义名在同一模块大小写不敏感唯一；活动用例名在同一定义内大小写不敏感唯一；冲突为 `409 NAME_CONFLICT`。归档定义不级联、不物理删除其用例。
- 名称 trim 后 1..256。method 仅 `GET/POST`。URL 仅允许无 userInfo/fragment 的绝对 HTTP(S) URL，或以 `/` 开头的相对路径；不得含空白/control 字符。
- URL 中 `{name}` 占位符与 `pathParams` 名称必须一一对应且不重复；path/query/header 名称非空，query 同名禁止，header 按大小写不敏感禁止同名；值必须为字符串，`enabled` 必须为 boolean。
- `${name}` 与 `${secret:name}` 只作为文本引用保存；拒绝未闭合/非法 token。普通变量名使用 `[A-Za-z_][A-Za-z0-9_.-]*` 且不得使用 `secret:` 保留前缀；密钥引用必须命中当前项目活动密钥，绝不展开或回显明文。
- `variables/requestSpec/caseSpec` 必须符合上述 JSON 类型；STATUS expected 为 100..599；JSON_PATH expression 必须为非空 `$` 根表达式，EXISTS 不得带 expected，EQUALS 必须带合法 JSON expected。
- 字段错误统一 `400 VALIDATION_FAILED`，details 为 `{"fieldErrors":[{"path":"requestSpec.body.value","code":"INVALID_JSON","message":"..."}]}`，禁止回显提交值；整体 JSON 无法解析为 `400 INVALID_REQUEST`，details 至少指向 `$`。

## 5. OpenAPI 文档

新增 `docs/api/f1-05-openapi.json`（OpenAPI 3.1，JSON 本身即合法 OpenAPI 文档），覆盖上述路径、DTO、枚举、201/400/401/403/404/409 与统一错误 schema。选择静态 JSON 是为了复用 Jackson 校验且不新增 springdoc 等生产依赖；文档不得出现 F1-06 页面、发送、Runner 或超范围 method/body/assertion。

新增 `OpenApiF105ContractTest`：Jackson 解析文档；断言 OpenAPI 版本、全部路径/方法、security、请求/响应 `$ref`、必填字段、enum 和错误响应；再与 Controller 的公开路径清单做双向比对，防止实现有未记录端点或文档声明不存在端点。

## 6. 实现顺序与验收

1. 先写真实 PostgreSQL 红灯：定义/用例创建、读取、更新与归档、JSONB 类型/数组顺序、GET/POST/JSON 合同、成功 revision+1 和陈旧 CAS。
2. 覆盖项目/模块/定义/用例交叉组合：所有跨项目 ID 404；归档项目拒写；归档定义拒绝用例写入；同名冲突和父定义不可变。
3. 字段级负向：非法 method/URL/占位符/参数重复/变量 token、非 object variables、错误 body/assertion/JSONPath；错误含 traceId 且不回显请求值或密钥。
4. OpenAPI 合同测试验证文档与 Controller 双向一致；确认 V1/V2 文件未改、无 V3/新表、无新增生产依赖。
5. F1-05 无新页面和执行链，不新增浏览器 E2E；运行现有前端测试/typecheck/build 只作回归门禁。

```powershell
wsl.exe -d Ubuntu -- bash -lc "cd /mnt/d/codexWorkSpec/autotest && mvn -pl platform-api -am -Dtest='*ApiDefinition*Test,*ApiCase*Test,*OpenApiF105*Test' -Dsurefire.failIfNoSpecifiedTests=false test"
npm.cmd --prefix web test -- --run
npm.cmd --prefix web run typecheck
npm.cmd --prefix web run build
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-local.ps1
```

只有真实 PostgreSQL 隔离/CAS/JSONB/字段校验、OpenAPI 双向合同和总门禁均有本轮新鲜证据，并经 Sol 复审通过，才允许进入 F1-06；这不代表接口已能发送或执行。
