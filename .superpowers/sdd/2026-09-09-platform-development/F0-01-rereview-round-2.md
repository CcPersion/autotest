# F0-01 第 2 轮独立复审报告

## 1. 复审结论

| 项目 | 结论 |
| --- | --- |
| 上轮 I-2 | **ADDRESSED** |
| 上轮 I-3 | **ADDRESSED** |
| 上轮 N-1 | **ADDRESSED** |
| 新增 Critical | 无 |
| 新增 Important | 1 项（N-2） |
| 规范符合性 | **FAIL** |
| 代码质量 | **REJECT** |
| 是否允许进入 F0-02 | **否** |

上一轮明确指出的 Authorization/Cookie 明文路径、缺失测试和 Bearer 模板误拒均已修复；13 项契约测试和全量构建也通过。但新实现把依赖资产路径语义的安全校验放进通用 `ExecutionPlan` DTO，并使用全局键名猜测，导致同一规则既漏过 `X-Api-Key` 明文，又误拒普通业务 JSON/变量。这是新的 Important，F0-01 仍不能放行。

## 2. 上一轮问题逐项判定

### I-2：ADDRESSED

- 文件：`shared-contracts/src/main/java/com/autotest/contracts/execution/ExecutionPlan.java:34-37,73-81,87-98,165-237`
- 结论依据：
  - `secretRefs` 继续只接受完整 `${secret:name}`；混入前后缀和非占位符形式会被拒绝。
  - `headers:[{name,value}]` 中的 Authorization 明文会被拒绝，Cookie Header 明文会被拒绝。
  - 直接 `authorization`、`cookie/cookies` 及结构化 Cookie 的上一轮复现路径均已加入校验。
  - 独立探针确认：Authorization 明文、Cookie 明文、引用后缀均被拒绝；合法结构化引用计划的 JSON 和 `toString()` 不含合成明文。
- 边界说明：此处的 ADDRESSED 仅表示上一轮 I-2 指出的具体 Authorization/Cookie 路径已闭合；新发现的其他敏感 Header 漏洞见 N-2。

### I-3：ADDRESSED

- 文件：`shared-contracts/src/test/java/com/autotest/contracts/ExecutionContractsTest.java:42-207,209-317`
- 结论依据：测试已增至 13 项，覆盖五类 DTO JSON 往返、JsonNode 构造输入与访问器隔离、JSON 类型/null/数值、集合防御复制、必要字段、严格 `secretRefs`、结构化 Authorization/Cookie、Bearer/Basic 合法模板、明文和后缀拒绝。
- 新鲜结果：`mvn -pl shared-contracts '-Dtest=ExecutionContractsTest' test` 退出码 0，13 项全部通过。
- 边界说明：现有测试满足上一轮要求，但未覆盖 N-2 所示的同类敏感 Header 和普通字段误伤，因此仍需补回归用例。

### N-1：ADDRESSED

- 文件：`shared-contracts/src/main/java/com/autotest/contracts/execution/ExecutionPlan.java:36-37,176-186,226-237`
- 结论依据：
  - exact `${secret:name}`、`Bearer ${secret:name}`、`Basic ${secret:name}` 被允许。
  - Bearer/Basic 明文、其他认证 scheme、引用后混入后缀均被拒绝。
  - 独立探针分别验证了直接字段和结构化 Header 行：Bearer、Basic 及 Cookie 引用可构造并 JSON 往返；`Basic ${secret:basic}suffix` 被拒绝。

## 3. 新发现

### Critical

无。

### Important

#### N-2 通用 DTO 的全局键名安全扫描同时存在漏拦截和误拦截

- 文件与行号：`shared-contracts/src/main/java/com/autotest/contracts/execution/ExecutionPlan.java:65-84,142-163,165-188,191-224,239-257`
- 漏拦截：`validateHeaderContainer` 只识别 Header 名 `Authorization` 和 `Cookie`。独立探针构造 `headers:[{name:"X-Api-Key",value:"SYNTHETIC_PLAINTEXT"}]` 后，计划成功构造，JSON 与 record 默认 `toString()` 均包含合成明文；`X-Auth-Token` 等同类名称也没有调用现有 `isSensitiveKey`。这与直接 JSON 键中已把 `apiKey`、`token` 视为敏感的规则不一致，仍不能证明执行计划不保存密钥明文。
- 误拦截：安全规则递归扫描所有 `assetContent` 和普通 `variables`，不区分请求 Header/Cookie 路径与业务 Body/任意 JSON：
  - 普通变量 `tokenizer: "standard"` 因名称包含 `token` 被拒绝；
  - 业务 Body 内名为 `headers` 的普通对象被强制解释成 Header 行数组并拒绝；
  - 任意业务字段名为 `cookie/cookies`、`passwordPolicy`、`authorizationMode` 等也可能被错误限制。
- 范围问题：F0-01 要求的是五类最小共享 DTO；环境密钥能力和执行计划组装分别属于后续 F1-04/F1-08。当前约 200 行路径猜测与局部资产 Schema 校验提前固化在共享 DTO 中，既超出最小契约职责，又因缺少完整 Schema 无法做到完备安全判断。
- 最小修复建议：
  - `ExecutionPlan` 保留 JsonNode 值域、深层不可变、类型稳定和严格 `secretRefs` 等结构性约束。
  - 将 Header/Cookie/敏感字段识别放到唯一、Schema 感知的执行计划组装校验中；依据明确资产路径、Header 行的 `name`、Cookie 结构和已知/用户配置的敏感字段判断，不在任意 Body/普通变量上按子串全局猜测。
  - 至少新增回归：`X-Api-Key`/`X-Auth-Token` 明文拒绝，普通 `X-Trace-Id` Header 允许，普通变量 `tokenizer` 允许，业务 Body 中普通 `headers` 对象允许；不得通过放宽为任意字符串来换取通过。

## 4. 其他重点核对

- JsonNode 不变性：构造后修改原始 ObjectNode、修改访问器返回的 ObjectNode，内部计划均不变；独立结果 `jsonNodeIsolation=true`。
- JSON 稳定性：合法 Bearer/Basic/Cookie 引用计划可 JSON 往返且 `equals=true`；null 与数值规范化逻辑未回退。
- 普通非敏感 Header：结构化 `X-Trace-Id: trace-123` 可正常构造，说明当前规则并非拒绝所有普通 Header；N-2 是路径/名称启发式的不一致，而非 Header 容器整体不可用。
- 说明边界：追加实施记录明确承认只覆盖 Authorization/Cookie，不宣称识别所有语义明文；文档没有把 JMeter、数据库队列、API 或浏览器流程冒充为 F0-01 已验证。

## 5. 新鲜验证证据

- `mvn -pl shared-contracts '-Dtest=ExecutionContractsTest' test`：退出码 0，13 项测试通过。
- `mvn clean test`：退出码 0；shared-contracts 13 项、platform-api 2 项、runner-app 3 项，共 18 项通过。
- `rg -n "engine-core|LeaseDescriptor|RunnerWriteEnvelope|RootSelector" pom.xml platform-api runner-app shared-contracts --glob '!**/target/**'`：无匹配，`rg` 退出码 1；`engine-core/` 不存在，POM 无旧模块声明。
- 独立无落盘 JShell 探针：
  - 通过：合法 Bearer/Basic/Cookie 引用、JSON 往返、序列化/`toString` 合成明文缺失、JsonNode 隔离；
  - 正确拒绝：Authorization/Cookie 明文、Bearer/Basic 引用后缀、非法 `secretRefs`；
  - 新失败证据：`xApiKeyPlaintextSerialized=true`、`xApiKeyPlaintextInToString=true`、`ordinaryTokenizerAccepted=false`、`ordinaryBodyHeadersObjectAccepted=false`。

## 6. 放行条件

修复 N-2 并新增对应的漏拦截/误拦截回归测试后，重新执行 13 项契约测试、全量干净构建、旧引用搜索和独立探针。当前不允许进入 F0-02。
