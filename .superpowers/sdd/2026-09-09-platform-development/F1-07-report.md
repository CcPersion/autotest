# F1-07 实施报告：基础平台计划到 JMX 的转换器

## 1. 状态与边界

- 状态：**实现完成（2026-09-11），Sol 复审待补**。
- 本阶段把一个可复用的单接口输入结构编译为可由固定 JMeter 5.6.3 CLI 读取和执行的临时 JMX。
- 范围只覆盖 GET Query、POST JSON、普通变量、Header、状态码断言和 JSONPath EXISTS/EQUALS 断言；不实现 F1-08 的任务队列、Runner 轮询、JMeter 子进程生命周期或 F1-09 报告。

## 2. 实现内容

- `runner-app/pom.xml` 精确锁定 `ApacheJMeter_core`、`ApacheJMeter_components`、`ApacheJMeter_http` 为 5.6.3；Java 17 测试增加 JMeter 所需的模块开放参数。
- 新增最小输入 DTO：`JmeterPlan`、`JmeterBody`、`JmeterParameter`、`JmeterAssertion`，不把 JMeter 依赖扩散到 Platform API 或 Web。
- `JmeterPlanCompiler` 使用 `HashTree` 和 `SaveService.saveTree` 写出 JMX；运行时优先使用 `JMETER_HOME/bin/saveservice.properties`，开发/测试 classpath 使用随 Runner 提供的 JMeter 5.6.3 属性资源。
- `JmeterComponentMapper` 只映射白名单组件：`TestPlan`、单线程/单次迭代 `ThreadGroup`、`HTTPSamplerProxy`、`HeaderManager`、`ResponseAssertion`、`JSONPathAssertion` 和用户变量；每个测试元素写入稳定 `stepId` 名称及 GUI/测试类元数据。
- 生成的 JMX 不包含 JSR223、BeanShell、JavaSampler、OS Process 或用户插件；变量挂在 `TestPlan.user_defined_variables`，POST JSON 使用 raw body。

## 3. TDD 证据

| 阶段 | 命令/结果 |
| --- | --- |
| 红灯 | 首次执行 `mvn.cmd -pl runner-app -am '-Dtest=JmeterPlanCompilerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test` 时，测试编译因 `JmeterPlanCompiler` 及最小 DTO 尚不存在而失败（预期缺实现红灯）。 |
| 绿灯 | `mvn.cmd -pl runner-app -am clean test '-Dtest=JmeterPlanCompilerTest' '-Dsurefire.failIfNoSpecifiedTests=false'`：定向转换测试 2/2 通过；XML 结构、组件数量、变量、Query、JSON body、两类 JSONPath 操作符、稳定 stepId 和禁用组件扫描均有断言。 |
| 主控复核 | `mvn.cmd -pl runner-app -am clean test '-Dsurefire.failIfNoSpecifiedTests=false'`：shared-contracts 16 项、runner 9 项全部通过；随后又以同一工作区执行 `mvn.cmd -pl runner-app -am test '-Dsurefire.failIfNoSpecifiedTests=false'`，结果仍为 16+9 全部通过。 |

## 4. 固定 JMeter 真实执行证据

- 使用既有固定镜像 `autotest/runner:0.1.0`（镜像内 JMeter 5.6.3、Java 17、非 root）和 WSL Docker CLI。
- 用随机精确名的本地 HTTP 服务容器返回 GET `200 {"orderId":"demo"}`、POST `201 {"accepted":true}`，未使用目标平台 API 或外部服务。
- 最新生成 JMX 的真实执行结果：
  - GET：`f1-07-get-7901860616946768849.jmx`，JMeter CLI exit 0，JTL 为 `HTTP Request [step-get-query]`、HTTP 200、`success=true`，Query 为 `tenant=demo`。
  - POST：`f1-07-post-17985270980640992884.jmx`，JMeter CLI exit 0，JTL 为 `HTTP Request [step-post-json]`、HTTP 201、`success=true`，JSON body 中变量替换生效。
- 两次运行后的本地 HTTP 容器均按随机容器名精确删除，并以 `docker ps -a --filter name=<本次容器名>` 复核无残留。JMeter 镜像输出的 package scanning deprecation warning 不影响执行结果。

## 5. 已知边界与未验证项

- 输入仍是 Runner 内部的单接口最小 DTO，尚未在 F1-07 组装 Platform API 的 `ExecutionPlan`/资产内容。
- 仅覆盖 GET/POST HTTP 基础请求；未实现 Path 参数替换、Cookie、文件、提取器、数据行、JDBC/Redis、控制流或插件映射。
- 未实现 F1-08 的数据库队列、任务领取、JMeter 子进程管理、取消、退出码回传和重启恢复；未实现 F1-09 的 JTL 结果适配、报告 API/页面。
- 本轮未运行根目录全量门禁，也未声称平台端到端执行闭环已完成；真实 CLI 只验证本阶段生成 JMX 的固定镜像可读性和基础断言执行。
