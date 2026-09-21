# F0-01 实施报告

## 1. 目标与范围

本任务按 `F0-01-brief.md` 执行，使工程保持“自研平台 + Apache JMeter 5.6.3 执行内核”方向。仅处理旧 Java 执行引擎、旧共享契约、模块依赖和契约测试；未实现 JMeter 编译/执行、队列、数据库或 Web API，未修改页面原型。

权威依据：

- `docs/requirements/01-产品需求规格说明书.md`：产品术语与执行计划边界、JMeter 架构边界。
- `docs/tasks/01-开发任务清单.md`：F0-01 目标、范围和验收条件。
- `.superpowers/sdd/2026-09-09-platform-development/F0-01-brief.md`：本次唯一实施简报。

## 2. TDD 红灯证据

先新增 `shared-contracts/src/test/java/com/autotest/contracts/ExecutionContractsTest.java`，覆盖五类必要 DTO 的 JSON 往返、集合不可变性和必要字段校验；此时没有新增生产 DTO。

命令：

```text
mvn -pl shared-contracts -Dtest=ExecutionContractsTest test
```

结果：退出码 `1`。测试编译阶段因 `com.autotest.contracts.execution` 包及 `ExecutionPlan`、`RunTask`、`RunEvent`、`StepResult`、`AttachmentRef` 均不存在而失败，符合预期红灯原因；不是断言误报。

## 3. 实际新增与修改

新增不可变 Java record 契约（均在 `com.autotest.contracts.execution`）：

- `ExecutionPlan`：计划标识、资产内容、普通变量、密钥引用、文件校验值、JMeter 版本和创建时间。
- `RunTask`：任务标识、运行标识、执行计划标识和创建时间。
- `RunEvent`：事件标识、运行标识、事件类型、时间、可选步骤结果及附件索引。
- `StepResult`：步骤标识、状态、消息、耗时和附件索引。
- `AttachmentRef`：附件标识、文件名、内容类型、大小和存储键。

所有契约均使用构造器校验必要文本/时间/非负数，并对集合做不可变复制；Jackson 可通过当前 Jackson 依赖对 `Instant` 和嵌套 record 完成往返。

修改：

- 根 `pom.xml` 删除 `engine-core` reactor 模块。
- `platform-api/pom.xml` 删除 `engine-core` 依赖。
- `runner-app/pom.xml` 删除 `engine-core` 依赖。
- `shared-contracts/pom.xml` 删除仅服务旧架构测试的 ArchUnit 测试依赖。

## 4. 实际删除

已在删除前使用 `Resolve-Path` 校验目标位于 `D:\codexWorkSpec\autotest` 内，并按精确路径删除：

- 整个 `D:\codexWorkSpec\autotest\engine-core` 目录。
- `shared-contracts/src/main/java/com/autotest/contracts/` 下旧 `agent`、`command`、`engine`、`event`、`expression`、`lease`、`model`、`scenario`、`snapshot`、`validation` 包目录。
- 15 个与旧契约无关的共享测试文件，包括旧快照、租约/fencing、纯 Java 执行端口、旧命令/事件、旧场景/表达式和旧架构测试；保留新 `ExecutionContractsTest`。

保留 `shared-contracts` 的 `util/ContractChecks`，供新契约复用；未删除或修改 `web` 页面原型。

## 5. 绿灯与验收证据

实现后，目标测试命令再次执行成功：

```text
mvn -pl shared-contracts -Dtest=ExecutionContractsTest test
```

结果：`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`。

为清理旧构建产物并验证干净 reactor，执行：

```text
mvn clean test
```

结果：退出码 `0`；shared-contracts 6 项、platform-api 架构测试 2 项、runner-app 架构测试 3 项，合计 11 项全部通过。

按简报要求执行根构建：

```text
mvn test
```

结果：退出码 `0`；同一 reactor 的 11 项测试全部通过。

按简报要求执行旧引用搜索：

```text
rg -n "engine-core|LeaseDescriptor|RunnerWriteEnvelope|RootSelector" pom.xml platform-api runner-app shared-contracts
```

结果：退出码 `1`（无匹配）。同时复核 `engine-core` 和所有旧 shared-contracts 包目录均不存在。

## 6. 遗留风险与未验证内容

- DTO 字段是 F0-01 所需的最小跨 Platform API/Runner 边界；后续 JMeter 转换、监听器、报告和队列任务可能需要在保持不可变/无租约边界的前提下扩展字段。
- 本任务没有验证 JMeter 5.6.3 安装、真实 JMX 执行、数据库队列、HTTP API、Docker Compose 或浏览器流程；这些属于后续 F0/F1 任务。
- 当前共享契约 JSON 测试使用 `ObjectMapper + JavaTimeModule`，调用方若自定义 Jackson 配置仍需在后续集成测试中验证。

## 7. 结论

F0-01 的代码、契约、删除边界和 Maven 验收均已按简报完成；后续可进入 F0-02 JMeter Runner 环境任务。没有需要用户额外处理的事项。

## 8. 第 1 轮审查修复追加记录（I-1/I-2/I-3）

本轮依据 `F0-01-review.md` 执行；未进入 F0-02。

### 8.1 修复前红灯

先扩展 `ExecutionContractsTest`，再运行：

```text
mvn -pl shared-contracts -Dtest=ExecutionContractsTest test
```

退出码为 `1`。编译错误明确显示测试要求的 `Map<String, JsonNode>` 执行计划 API 尚不存在，当前生产代码仍是 `Map<String, Object>`/`Map<String, String>`；这是待修复契约类型缺失导致的红灯。

### 8.2 实际修复

- `ExecutionPlan.assetContent` 和 `variables` 改为 `Map<String, JsonNode>`，拒绝非 JSON 值。
- 构造时递归复制对象/数组，允许 JSON `null`，并把整数/小数规范为稳定的 `DecimalNode` 表示；访问器每次返回新的递归 `deepCopy`，同时顶层 Map 不可修改。
- `secretRefs` 仅接受严格 `${secret:name}` 占位符；`assetContent`/`variables` 顶层和递归对象中的 `password`、`secret`、`token`、`apiKey`、`authorization` 等敏感键只允许同样的占位符。
- 扩展测试覆盖嵌套对象/数组构造后和 accessor 返回值修改、字符串/数字/布尔/对象/数组/null 类型、JSON 往返 equals、密钥哨兵、所有集合的输入防御复制/返回不可修改，以及五类 DTO 必要字段校验。
- 未恢复旧 `JsonValue`、快照、租约或多引擎框架。

修复过程中首次绿灯尝试发现顶层敏感键未纳入递归校验，补上顶层检查后再次运行目标测试通过。

### 8.3 修复后绿灯

目标测试：

```text
mvn -pl shared-contracts -Dtest=ExecutionContractsTest test
```

结果：退出码 `0`，`Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`。

全量干净构建：

```text
mvn clean test
```

结果：退出码 `0`；shared-contracts 10 项、platform-api 2 项、runner-app 3 项，共 15 项全部通过。

根测试复核：

```text
mvn test
```

结果：退出码 `0`；同一 15 项测试全部通过。

旧引用搜索：

```text
rg -n "engine-core|LeaseDescriptor|RunnerWriteEnvelope|RootSelector" pom.xml platform-api runner-app shared-contracts
```

结果：退出码 `1`，无匹配。

### 8.4 修复后关注点

- 敏感键识别是本任务可证明的最小边界，仅覆盖明确的键名片段；它不宣称能识别所有语义上的明文密钥。F1-04 的组装服务仍需负责已知密钥值哨兵扫描和运行时密钥隔离。
- 本轮仍未验证 JMeter 5.6.3、真实 JMX、数据库队列、Web API、Docker Compose 或浏览器流程；这些继续属于后续任务。

## 9. 第 2 轮审查修复追加记录（结构化 Header/Cookie 密钥边界）

本轮仍只修改 `shared-contracts`，未启动 F0-02，未新增生产依赖或恢复旧模型。

### 9.1 修复前红灯

先在 `ExecutionContractsTest` 新增结构化安全边界测试，再运行：

```text
mvn -pl shared-contracts -Dtest=ExecutionContractsTest test
```

结果：退出码 `1`，13 项中 3 项失败：

- `headers:[{name:"Authorization",value:"SYNTHETIC_PLAINTEXT"}]` 未被拒绝；
- 直接 `authorization:"Bearer ${secret:token}"` 被旧的 exact-placeholder 规则错误拒绝；
- `cookie/cookies` 明文未被拒绝。

### 9.2 实际修复

- `headers` 只按明确的 Header 行 `{name,value}` 结构检查：Authorization 仅允许 exact `${secret:name}` 或 exact `Bearer ${secret:name}` / `Basic ${secret:name}`；Cookie Header 的 value 仅允许 exact `${secret:name}`。
- 直接 `authorization` 字段使用同一严格 Authorization 模板；前后增加不受控文本、其他认证 scheme 和 Bearer 明文均拒绝。
- `cookie/cookies` 支持 exact secret placeholder、`{name,value}` 结构和 name-to-placeholder 映射；结构化 Cookie 的每个 value 必须是 exact `${secret:name}`，明文被拒绝。
- 允许的计划 JSON 与 record `toString()` 均验证不含合成明文哨兵；原有 JsonNode 深拷贝、数值稳定和类型保持逻辑未改变。

### 9.3 修复后绿灯

目标测试：

```text
mvn -pl shared-contracts -Dtest=ExecutionContractsTest test
```

结果：退出码 `0`，`Tests run: 13, Failures: 0, Errors: 0, Skipped: 0`。

干净全量构建：

```text
mvn clean test
```

结果：退出码 `0`；shared-contracts 13 项、platform-api 2 项、runner-app 3 项，共 18 项全部通过。

旧引用搜索：

```text
rg -n "engine-core|LeaseDescriptor|RunnerWriteEnvelope|RootSelector" pom.xml platform-api runner-app shared-contracts
```

结果：退出码 `1`，无匹配。

### 9.4 本轮关注点

- 结构化校验仅覆盖已明确的 Header Authorization/Cookie 和 Cookie 容器形状；不宣称可识别所有任意字段中的语义明文。F1-04 仍需负责已知密钥值哨兵扫描与运行时隔离。
- JMeter、真实 JMX、数据库队列、Web API、Docker Compose 和浏览器流程仍未验证，属于后续任务；本轮未进入 F0-02。

## 10. 第 3 轮审查修复追加记录（Header 误报与敏感名精确化）

本轮仍只修改 `shared-contracts`，未启动 F0-02。

### 10.1 修复前红灯

先新增三类回归测试，再运行：

```text
mvn -pl shared-contracts -Dtest=ExecutionContractsTest test
```

结果：退出码 `1`，16 项中 3 项失败：

- `headers:[{name:"X-Api-Key",value:"SYNTHETIC_PLAINTEXT"}]` 未被拒绝；
- 普通变量 `tokenizer="standard"` 被 `token` 子串规则误拒；
- 业务 body 内普通 `headers` 对象被错误要求为 Header 行数组。

### 10.2 实际修复

- 将敏感 JSON 字段改为规范化后的精确名称集合，去除 `contains` 误判；`tokenizer`、`tokenCount` 等普通名称不再触发密钥规则。
- Header 行校验仅在节点为数组且每个元素确实含 `name`、`value` 字段时启用。
- Header 敏感名按规范化精确匹配：Authorization、Proxy-Authorization、Cookie、Set-Cookie、X-Api-Key、API-Key；普通 Header 仍正常通过。
- 业务 body 中的 `headers` 对象不再被当作 Header 行容器；其内部普通 JSON 递归校验保持可用。
- 保留 Authorization 的 exact ref/Bearer/Basic ref 规则、其他敏感 Header/Cookie 的 exact ref 规则，以及 JsonNode 深拷贝和数值稳定逻辑。

### 10.3 修复后绿灯

目标测试：

```text
mvn -pl shared-contracts -Dtest=ExecutionContractsTest test
```

结果：退出码 `0`，`Tests run: 16, Failures: 0, Errors: 0, Skipped: 0`。

干净全量构建：

```text
mvn clean test
```

结果：退出码 `0`；shared-contracts 16 项、platform-api 2 项、runner-app 3 项，共 21 项全部通过。

旧引用搜索：

```text
rg -n "engine-core|LeaseDescriptor|RunnerWriteEnvelope|RootSelector" pom.xml platform-api runner-app shared-contracts
```

结果：退出码 `1`，无匹配。

### 10.4 本轮关注点

- 当前 Header/Cookie 防护只对明确识别的结构和敏感名称生效，不宣称可以识别任意业务字段中的语义明文；F1-04 仍需负责已知密钥值哨兵替换与运行时隔离。
- JMeter、真实 JMX、数据库队列、Web API、Docker Compose 和浏览器流程仍未验证；本轮未进入 F0-02。
