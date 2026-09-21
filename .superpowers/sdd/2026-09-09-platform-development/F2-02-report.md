# F2-02：变量、内置函数和运行上下文验收记录

## 状态

- 状态：已完成。
- Luna 实现与测试：通过。
- Sol 独立最终复审：`PASS / APPROVE`。
- 完成日期：2026-09-19。

## 实现摘要

- 共享契约统一 Platform 与 Runner 的 `formatdate` 校验：非空、方括号配对、`DateTimeFormatter` 和 `Locale.ROOT`。
- 固定变量优先级：提取值 > 数据行 > 接口用例 > 场景 > 环境；保留旧 `variables` 入口。
- 完整 JSON 变量保持原类型，混合文本使用字符串插值；普通变量缺失在外部触达前失败。
- Platform 在普通运行和调试运行入队前执行变量预检；集合成员使用各自作用域；启用数据行逐行预检。
- Runner 的普通集合成员不传播兄弟成员提取值；成员内部场景仍保留跨步骤上下文。

## 验证证据

Luna targeted 命令：

```text
wsl.exe bash -lc 'cd /mnt/d/codexWorkSpec/autotest && mvn -pl platform-api,runner-app -am -Dtest=DateTimePatternChecksTest,RunServiceTest,RunPostgresqlIntegrationTest,ExecutionPlanAdapterTest,RunVariableContextTest,RunnerWorkerTest -Dsurefire.failIfNoSpecifiedTests=false test -q'
```

结果：64 项通过，0 失败、0 错误、0 跳过。

全量命令：

```text
wsl.exe bash -lc 'cd /mnt/d/codexWorkSpec/autotest && mvn -pl shared-contracts,runner-app,platform-api -am clean test'
```

结果：退出码 0；shared-contracts 26、platform-api 147、runner-app 156；0 失败、0 错误，3 项 F107/F108/F404 镜像专项因未配置对应镜像而跳过。

Sol 复审重点确认：

1. Platform 与 Runner 使用同一日期格式合同。
2. 集合成员 A 的提取值不能覆盖成员 B 自身变量。
3. 场景成员仍使用独立 `ScenarioRunState`，成员内提取可继续传播。
4. 上一轮的成员变量作用域、逐行预检、入队接线和文本类型保持问题均已关闭。

## 非阻断风险

- 默认日期格式目前在两端仍有相同字面量；未来修改时应统一引用共享常量。
- 尚无专门的“集合 + 场景 + 提取”组合测试，但底层 `executeScenario` 跨步骤提取已有回归覆盖。

## 下一步

进入任务清单中的 F2-03：完整提取与断言，继续执行 Luna → 测试 → Sol 的双模型门禁。
