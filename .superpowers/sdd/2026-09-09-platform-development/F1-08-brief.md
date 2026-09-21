# F1-08 单 Runner 数据库队列与 JMeter 子进程

## 目标

在不引入多 Runner 租约、fencing、消息队列或新的执行引擎的前提下，把一个已保存的执行计划交给单个 Runner，完成领取、生成临时 JMX、调用固定 JMeter CLI、保存退出结果和取消。

## 必须交付

- Platform API 增加最小运行接口：创建运行、查询运行、取消运行；创建使用现有 `runs` 表，保留 `execution_plan` JSONB、项目/环境约束和 `idempotency_key` 幂等语义。
- Runner 使用单一数据库队列领取 `PENDING` 运行，领取必须是数据库原子操作；同一运行不能被两次领取。
- Runner 领取后在独立工作目录生成 JMX，使用固定 JMeter 5.6.3 的 `jmeter -n -t ... -l ...` 子进程执行；退出码为 0 进入成功，非 0 进入失败，但 Runner 主进程不得退出。
- 取消只允许 PENDING/RUNNING；运行中的 JMeter 先正常销毁，超时后强制销毁，最终状态为 `CANCELED`。
- Runner 启动时把遗留的 RUNNING 标记为 `INTERRUPTED`；工作目录按 runId 隔离，不能穿越到目录外。
- 平台和 Runner 日志、错误信息不得包含执行计划中的密钥明文；只接受 `${secret:name}` 占位符。

## 实现约束

- 复用现有 V1 `runs` 表，不新增复杂版本/租约/fencing 数据模型；如确需字段，必须说明原因并补 Flyway 迁移。
- Runner 不依赖 Spring；平台仍保持 Spring Boot。Runner 可使用 PostgreSQL JDBC 和现有 shared-contracts/JmeterPlanCompiler。
- 不实现 F1-09 的 JTL 解析、报告 API、SSE 或步骤结果回传；本任务只记录运行状态、退出码和诊断路径。
- 不允许 JSR223、BeanShell、OS Process、用户插件或任意用户脚本进入生成 JMX。

## 最小验收

- Testcontainers PostgreSQL：两次并发领取同一 PENDING 运行只有一次成功；幂等键重复创建返回同一运行。
- 真实本地 Mock HTTP + 固定 JMeter：成功请求和断言失败各有正确状态；JMeter 非 0 后 Runner 仍可继续领取下一条任务。
- 取消、启动恢复、工作目录边界和敏感信息脱敏有定向测试；随机测试容器、进程和目录均精确清理。
