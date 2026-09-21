# F0-01 实施简报

## 目标

让工程代码结构与“自研平台 + Apache JMeter 5.6.3 执行内核”保持一致，删除已废弃的纯 Java 执行引擎、复杂快照、多选择器、租约和 fencing 契约。

## 必须修改

- 从根 `pom.xml` 删除 `engine-core` 模块。
- 从 `platform-api/pom.xml` 和 `runner-app/pom.xml` 删除对 `engine-core` 的依赖。
- 删除整个 `engine-core/` 目录。
- 精简 `shared-contracts`：删除旧 `snapshot`、`lease`、纯 Java执行端口、旧命令/事件和与新基线无关的测试。
- 只保留并按需要新建 JMeter Runner 后续会使用的必要契约：`ExecutionPlan`、`RunTask`、`RunEvent`、`StepResult`、`AttachmentRef`。契约应不可变、字段命名清楚、构造时校验必要字段，并可用当前 Jackson 依赖完成 JSON 往返。
- `platform-api` 和 `runner-app` 的架构测试只能依赖 `shared-contracts`，不得引用已删除模块或旧契约。

## 明确不做

- 不实现 JMeter 编译、执行、队列、数据库或 Web API；这些属于后续任务。
- 不引入 Spring、Lombok、消息队列或新生产依赖。
- 不恢复多 Runner 租约、fencing、多选择器或通用多引擎抽象。
- 不修改页面原型。

## 测试先行

先新增针对五类必要 DTO 的契约和 JSON 往返测试并确认红灯，再实现最小 DTO。删除旧模块/契约后执行：

```text
mvn test
rg -n "engine-core|LeaseDescriptor|RunnerWriteEnvelope|RootSelector" pom.xml platform-api runner-app shared-contracts
```

第二条命令允许测试或说明中出现任务名时单独判断，但生产代码和 POM 中必须零引用。

## 验收

- 根 Maven 构建全部通过。
- `engine-core/` 不存在。
- POM 与生产源码中不存在上述四个旧名称。
- 五类 DTO 至少各有一次 JSON 往返覆盖。
