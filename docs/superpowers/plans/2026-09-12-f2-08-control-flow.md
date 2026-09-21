# F2-08 控制流首片实施计划

## 目标

在现有场景步骤模型上补齐无脚本控制流首片：条件、固定次数循环、列表遍历循环、条件循环和固定等待。控制流仍由平台保存结构化配置，Runner 直接执行时使用同一份计划；JMeter 侧只生成受控的 If/Loop/ForEach/While 结构，不开放脚本、函数和任意组件。

## 配置契约

- `CONDITION`：`operator` 为 `EQUALS`、`NOT_EQUALS`、`CONTAINS`、`NOT_CONTAINS`、`GREATER_THAN`、`LESS_THAN`、`EXISTS` 或 `NOT_EXISTS`；`left` 为变量表达式或常量，`right` 为可选比较值。子步骤通过 `stepConfig.branch` 标记 `THEN` 或 `ELSE`，缺省按 `THEN` 处理。
- `LOOP`：`mode` 为 `FIXED`、`LIST` 或 `WHILE`；固定循环使用 `count`；列表循环使用 `items`、`itemVariable`；条件循环复用条件字段。所有模式必须有 `maxIterations`，默认 100，最大 1000，防止无限执行。
- `WAIT`：沿用 `waitMillis`，范围为 0 到 24 小时。
- 步骤计划额外带 `parentId` 和 `position`，Runner 按父子关系构造执行树。禁用步骤和未命中的分支不产生实际请求样本。

## 安全边界

- 变量只从执行上下文解析，不执行表达式语言、脚本或反射调用。
- 条件比较只支持标量；列表必须是 JSON 数组或逗号分隔的标量文本。
- 循环次数受 `maxIterations` 限制；配置缺失、类型不匹配和超过上限均按步骤失败处理。
- JMeter 映射只允许 IfController、LoopController、ForeachController、WhileController 和平台已注册的子步骤；不生成 JSR223、BeanShell、Groovy 或任意脚本组件。

## 验收

1. 平台拒绝未知运算符、负数/超过上限的循环配置、缺失列表变量和条件循环缺少条件。
2. Runner 定向测试覆盖条件 THEN/ELSE、固定循环、列表遍历、条件循环最大次数、等待取消和禁用分支不产生 HTTP 请求。
3. JMX 结构测试确认四种控制器存在，且产物不含脚本组件和用户输入的任意控制器类。
4. Web 场景属性面板可以编辑条件、循环和等待配置，并保存为上述结构化字段。
5. 根 `scripts/verify-local.ps1` 全量门禁通过。

## 本轮不做

- 不实现嵌套场景引用、复杂布尔表达式、随机循环、并发控制和 F2-09 重试/取消/独立清理语义。
- 不改变已有 HTTP、JDBC、Redis 执行器及其安全边界。
