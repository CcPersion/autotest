# F0-01 第 3 轮最终限定复审报告

## 1. 结论

| 项目 | 结论 |
| --- | --- |
| round2 N-2 | **ADDRESSED** |
| 规范符合性 | **PASS** |
| 代码质量 | **APPROVE** |
| 是否允许进入 F0-02 | **是** |

本轮严格限定核验 round2 N-2 的两个根因复现及直接回归。X-Api-Key/API-Key Header 明文拒绝且密钥引用允许；普通 `tokenizer` 变量和业务 Body 内普通 `headers` 对象允许；原 Authorization/Cookie、JSON 往返与 JsonNode 不变性均无回退。未发现本轮范围内新的 Critical 或 Important。

## 2. N-2 关闭证据

### 2.1 敏感 API-Key Header

- 文件：`shared-contracts/src/main/java/com/autotest/contracts/execution/ExecutionPlan.java:39-44,172-190,266-271`
- `X-Api-Key: SYNTHETIC_PLAINTEXT`：独立探针结果 `xApiKeyPlaintextRejected=true`。
- `API-Key: SYNTHETIC_PLAINTEXT`：独立探针结果 `apiKeyPlaintextRejected=true`。
- `X-Api-Key: ${secret:x-api-key}` 与 `API-Key: ${secret:api-key}`：均成功构造，结果 `apiKeyRefsAllowed=true`。
- `${secret:x-api-key}suffix`：被拒绝，结果 `apiKeySuffixRejected=true`。

结论：round2 的 API-Key Header 明文漏拦截已关闭，且没有通过禁止合法引用来规避问题。

### 2.2 普通变量和业务 JSON 不再误伤

- 文件：`shared-contracts/src/main/java/com/autotest/contracts/execution/ExecutionPlan.java:72-91,149-169,193-203,266-271`
- 普通变量 `tokenizer: "standard"`：成功构造并保持原值，结果 `tokenizerAllowed=true`。
- 业务 Body 中普通 `headers: {display:"ordinary-business-value"}`：成功构造并保持结构，结果 `bodyHeadersObjectAllowed=true`。
- 实现已把敏感字段从子串判断改为规范化后的精确名称集合，并仅在 `headers` 确实是全部含 `name/value` 的行数组时启用 Header 语义校验。

结论：round2 的 `tokenizer` 与 `body.headers` 误拒均已关闭。

## 3. 直接回归核验

- Authorization：结构化 Header 中合法 `Bearer ${secret:token}`、`Basic ${secret:basic}` 可构造；Bearer 明文仍被拒绝。
- Cookie：结构化 Cookie 引用和 Cookie Header 引用可构造；Cookie 明文仍被拒绝。
- JSON 往返：包含 Authorization/Cookie、Long 数值和 JSON null 的计划往返后 `equals=true`。
- JsonNode 不变性：修改构造输入节点和访问器返回节点均不影响内部计划，结果 `jsonNodeIsolation=true`。
- 序列化与 `toString()`：允许构造的限定安全计划中均无合成明文哨兵，结果 `safeSerializationAndToString=true`。
- F1-04 边界保持：本轮不扩展枚举任意语义秘密；已知密钥值替换和运行时隔离仍由 F1-04 后续能力承担。

## 4. 测试与工程门禁

- `mvn -pl shared-contracts '-Dtest=ExecutionContractsTest' test`：退出码 0，16 项测试全部通过。
- `mvn clean test`：退出码 0；shared-contracts 16 项、platform-api 2 项、runner-app 3 项，共 21 项全部通过。
- `rg -n "engine-core|LeaseDescriptor|RunnerWriteEnvelope|RootSelector" pom.xml platform-api runner-app shared-contracts --glob '!**/target/**'`：无匹配，`rg` 退出码 1。
- `engine-core/`：不存在。

## 5. 最终判定

round2 N-2 已按限定验收集关闭，原有关键契约没有回退。F0-01 规范符合性 PASS、代码质量 APPROVE，允许进入 F0-02。
