# F0-01 第 1 轮修复复审报告

## 1. 结论

| 项目 | 结论 |
| --- | --- |
| I-1 | **ADDRESSED** |
| I-2 | **NOT ADDRESSED** |
| I-3 | **NOT ADDRESSED** |
| 新增 Critical | 无 |
| 新增 Important | 1 项 |
| 规范符合性 | **FAIL** |
| 代码质量 | **REJECT** |
| 是否允许进入 F0-02 | **否** |

`ExecutionPlan` 的 JsonNode 深复制、访问器隔离、JSON 类型/null/数值稳定性已修复；但“执行计划不保存密钥明文”仍无法由当前校验保证，且测试未覆盖实际 Header 行结构这一可复现泄漏路径。修复还引入了对安全 Authorization 模板的误拒绝。因此不得进入 F0-02。

## 2. 原问题逐项复审

### I-1：ADDRESSED

- 文件：`shared-contracts/src/main/java/com/autotest/contracts/execution/ExecutionPlan.java:26-27,39-40,51-61,63-78,95-134`
- 判定依据：
  - `assetContent`、`variables` 已改为 `Map<String, JsonNode>`。
  - 构造时递归复制 Object/Array，JSON `null` 规范为 `NullNode`，所有数值规范为稳定的 `DecimalNode`。
  - 两个访问器均返回新的深拷贝和不可修改顶层 Map，修改输入节点或访问器返回节点均不能影响内部状态。
  - 独立无落盘探针结果：`deepIsolation=true`、`jsonRoundTripEqual=true`、`nullPreserved=true`，数值往返后稳定为 `DecimalNode`。
- 结论：原 I-1 的深层可变、类型丢失、JSON null 和数值往返不等问题均已处理，未恢复旧快照或多引擎模型。

### I-2：NOT ADDRESSED

- 文件：`shared-contracts/src/main/java/com/autotest/contracts/execution/ExecutionPlan.java:34-35,71-75,81-92,136-169`
- 已处理部分：
  - `secretRefs` 已严格要求完整 `${secret:name}` 格式；`secret:token`、`${secret:}` 和直接明文会被拒绝。
  - 以 `password`、`secret`、`token`、`apiKey`、`authorization` 作为直接 JSON 键时，明文会被拒绝，嵌套对象也会递归检查。
- 未处理原因：当前实现按“JSON 字段名包含敏感词”猜测语义，不能覆盖平台资产的常见结构。独立探针使用典型 Header 行：

  ```json
  {"headers":[{"name":"Authorization","value":"SYNTHETIC_PLAINTEXT"}]}
  ```

  同时在普通变量中放入 `cookie: SYNTHETIC_PLAINTEXT`。该计划构造成功，Jackson 输出仍包含合成明文（`semanticHeaderPlaintextAccepted=true`）。`Cookie` 还是需求明确要求脱敏的字段，但 `isSensitiveKey` 未覆盖它。由于 record 的默认 `toString` 也会包含内部 Map，一旦绕过校验，日志同样存在泄漏面。
- 最小修复建议：不要仅依赖任意 JsonNode 的键名子串扫描。建立唯一、按平台资产结构识别 Header/Cookie/认证字段及普通变量来源的计划组装校验；所有已知密钥值只能以引用表达式进入计划，并用合成密钥哨兵覆盖 `name/value` Header、Cookie、嵌套请求数据和序列化/`toString` 路径。若 F0 暂无完整资产 Schema，应至少把当前“无明文保证”门禁保留为未完成，而不能提前放行。

### I-3：NOT ADDRESSED

- 文件：`shared-contracts/src/test/java/com/autotest/contracts/ExecutionContractsTest.java:42-111,113-139,141-208,210-250`
- 已处理部分：五类 DTO 均有 JSON 往返覆盖；JsonNode 输入/访问器深层隔离、所有集合的防御复制与不可修改、五类 DTO 的代表性必要字段校验均已补齐。
- 未处理原因：密钥测试只覆盖“敏感词直接作为 JSON 键”的形态。`assertFalse(json.contains(SECRET_SENTINEL))` 所序列化的计划从未放入该哨兵，不能证明真实资产结构不会泄漏；测试缺少上节已复现的 `headers:[{name,value}]`、Cookie 及安全模板场景。因此测试仍不能证明原 I-2 的明文阻断边界。
- 最小修复建议：保留现有 10 项测试，新增上述实际结构的失败用例；至少要求合成明文无法构造或无法序列化，并要求合法引用模板可构造和往返。

## 3. 修复引入的新 Important

### N-1 安全的 Header 模板被误拒绝

- 文件：`shared-contracts/src/main/java/com/autotest/contracts/execution/ExecutionPlan.java:71-73,142-143,163-168`
- 原因：敏感键的值必须与 `${secret:name}` 完全相等，导致常见且不含密钥明文的 `authorization: "Bearer ${secret:token}"` 被 `IllegalArgumentException` 拒绝；独立探针结果为 `safeBearerTemplateAccepted=false`。需求允许变量用于 Header，并规定密钥使用 `${secret:name}`，不能把安全引用在 Header 模板中的正常组合封死。
- 最小修复建议：对敏感字段使用结构化的“固定文本 + 密钥引用”表达式，或在计划组装层解析并验证模板的每个片段；不得通过放宽为任意字符串来修复，否则会重新引入明文泄漏。

## 4. 新鲜验证

- `mvn -pl shared-contracts '-Dtest=ExecutionContractsTest' test`：退出码 0，10 项测试全部通过。
- `mvn clean test`：退出码 0；shared-contracts 10 项、platform-api 2 项、runner-app 3 项，共 15 项全部通过。
- `rg -n "engine-core|LeaseDescriptor|RunnerWriteEnvelope|RootSelector" pom.xml platform-api runner-app shared-contracts --glob '!**/target/**'`：无匹配，`rg` 退出码 1；`engine-core/` 不存在。
- 无落盘 JShell 探针：I-1 各项通过；严格 `secretRefs` 和直接 `password` 明文拒绝通过；实际 Header 行/Cookie 明文仍可序列化，安全 Bearer 引用模板被误拒绝。

## 5. 放行条件

修复 I-2、补齐 I-3 的实际资产结构安全测试，并消除 N-1 后，重新执行目标契约测试、`mvn clean test`、旧引用搜索和独立明文哨兵探针；全部通过后方可进入 F0-02。
