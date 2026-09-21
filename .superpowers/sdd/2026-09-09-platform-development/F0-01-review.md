# F0-01 独立架构与代码审查报告

## 1. 审查结论

| 项目 | 结论 |
| --- | --- |
| 规范符合性 | **FAIL** |
| 代码质量 | **REJECT** |
| 是否允许进入 F0-02 | **否** |

工程方向和删除边界符合“自研平台 + Apache JMeter 5.6.3 执行内核、首版单 Runner、无租约/fencing/多选择器”的批准范围；但 `ExecutionPlan` 当前不能满足简报明确要求的“不可变、JSON 往返稳定”，且密钥引用契约允许直接承载明文。现有测试没有覆盖并阻止这些问题，因此 F0-01 尚未达到进入 F0-02 的契约门禁。

## 2. 分级问题

### Critical

无。

### Important

#### I-1 `ExecutionPlan` 的资产与变量表示不满足不可变和类型保持要求

- 文件与行号：`shared-contracts/src/main/java/com/autotest/contracts/execution/ExecutionPlan.java:12-15,22-25`
- 依据：`docs/requirements/01-产品需求规格说明书.md:155,227,296`；`.superpowers/sdd/2026-09-09-platform-development/F0-01-brief.md` 的不可变及 JSON 往返要求。
- 原因：
  - `Map.copyOf(assetContent)` 只复制最外层。无落盘 JShell 探针在构造后修改原始嵌套 `List`，`plan.assetContent()` 立即观察到变化（`nestedMutationVisible=true`），所以该 record 并非真正不可变。
  - `Map<String,Object>` 没有限定 JSON 值域，也没有数值规范化。同一探针中 `Long(1)` 经 Jackson 往返变成 `Integer(1)`，`ExecutionPlan.equals` 返回 `false`；顶层合法 JSON `null` 值还会被 `Map.copyOf` 以 `NullPointerException` 拒绝。
  - `variables` 使用 `Map<String,String>`，与普通变量必须支持字符串、数字、布尔和 JSON 并保持原始类型的需求冲突。实测从 JSON 反序列化后数字和布尔均变成 `String`，JSON 对象值产生 `MismatchedInputException`。
- 最小修复建议：将 `assetContent` 和 `variables` 收敛为受约束的 JSON 值表示，构造时递归防御复制并规范化数值、允许 JSON `null`，访问时不得暴露可变容器；不要恢复旧快照模型或引入通用多引擎抽象。新增嵌套集合、数值、布尔、JSON 对象/数组和 `null` 的构造后变更与 JSON 往返测试。

#### I-2 `secretRefs` 及执行计划构造边界不能阻止密钥明文进入持久化 JSON

- 文件与行号：`shared-contracts/src/main/java/com/autotest/contracts/execution/ExecutionPlan.java:13-14,23-24`
- 依据：`docs/requirements/01-产品需求规格说明书.md:167-168,228,296`。
- 原因：`secretRefs` 是未经格式或语义校验的任意字符串列表，`variables`/`assetContent` 也没有强制的计划组装边界。无落盘探针证明合成明文可直接放入 `secretRefs` 和 `variables` 并成功构造，随后会被 Jackson 原样序列化。这不能证明“执行计划不保存密钥明文”。
- 最小修复建议：让 `secretRefs` 只接受明确的密钥引用标识/占位符（可用最小值对象或严格校验），并由唯一的执行计划构造入口保证解析后的密钥值不能进入 `secretRefs`、普通变量或资产快照；密钥只在 Runner 执行前解析到受限临时文件。加入合成密钥哨兵测试，断言计划 JSON 不包含该明文。

#### I-3 契约测试虽覆盖五类 DTO 往返，但不能证明构造校验和不可变性

- 文件与行号：`shared-contracts/src/test/java/com/autotest/contracts/ExecutionContractsTest.java:28-46,58-71,74-100`
- 原因：
  - 五类 DTO 都有至少一次 JSON 往返，这是已满足部分。
  - `executionPlanRoundTripsAsJsonAndCopiesCollections` 修改了输入 `assetSnapshot`，但没有断言新增键未进入计划；其嵌套集合本来就是 `List.of/Map.of`，无法发现浅复制问题。
  - 只断言 `variables` 顶层不可修改，未覆盖 `assetContent` 的深层结构、`secretRefs`、`fileChecksums`、`RunEvent.attachments` 和 `StepResult.attachments` 的防御复制/不可修改行为。
  - 构造校验测试仅抽查 `RunTask.taskId`、`AttachmentRef.sizeBytes` 和 `StepResult.status`，没有覆盖 `ExecutionPlan`、`RunEvent` 的必要字段，也没有密钥引用和变量类型边界测试。
- 最小修复建议：先补能稳定复现 I-1/I-2 的失败测试，再做最小实现；测试至少覆盖五类 DTO 的各自必要字段、每个集合组件的输入防御复制和返回值不可修改，以及执行计划的复杂 JSON 往返和明文密钥拒绝。

### Minor

无。本次按 F0-01 门禁仅记录阻止进入下一任务的问题。

## 3. 已通过的边界检查

- 根 `pom.xml:15-19` 的 reactor 仅包含 `shared-contracts`、`platform-api`、`runner-app`；`engine-core/` 实际不存在。
- `platform-api/pom.xml:14-37` 与 `runner-app/pom.xml:14-37` 的唯一项目内生产依赖均为 `shared-contracts`；未发现恢复纯 Java 执行引擎、租约、fencing 或多 Runner 依赖。
- 精确搜索 `engine-core|LeaseDescriptor|RunnerWriteEnvelope|RootSelector` 于根/三模块 POM 和生产源码中无匹配；旧 `snapshot`、`lease`、`engine`、`command`、`event` 包目录均不存在。
- `platform-api` 与 `runner-app` 架构测试不引用已删除模块或旧契约，现有模块方向约束与批准边界一致。
- 删除未发现误删后续仍必须保留的实现：F0-01 明确要求删除旧执行端口和旧快照/租约契约；当前保留五类执行 DTO 与 `ContractChecks`，JMeter 编译、执行和丰富报告字段按 F1-07 至 F1-09 后续任务实现。
- `RunTask` 的任务/运行/计划关联字段和 `AttachmentRef` 的对象存储索引属于合适的 F0 最小字段；`RunEvent`/`StepResult` 作为事件包络与最小步骤结果不会迫使本任务提前实现 6.12 的完整报告结构，丰富结果字段应在 F1-09 使用前按真实监听器数据补齐。

## 4. 本轮新鲜验证

- `mvn clean test`：退出码 0；从干净 `target` 重新编译，`shared-contracts` 6 项、`platform-api` 2 项、`runner-app` 3 项，共 11 项测试通过。
- 旧引用精确搜索：无匹配，`rg` 退出码 1（表示未找到）。
- 无落盘 JShell 契约探针：稳定复现 `nestedMutationVisible=true`、`longRoundTripEqual=false`、合法顶层 JSON `null` 被拒绝、普通变量类型丢失、JSON 变量被拒绝，以及合成明文可进入执行计划。

## 5. 放行条件

修复 I-1 至 I-3 后，重新执行 `mvn clean test`、旧引用搜索及新增的深层不可变/类型保持/密钥哨兵测试；全部通过并经独立复核后，方可进入 F0-02。
