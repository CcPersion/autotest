# F4-03：审计、Runner 状态与运维恢复首片实施计划

## 目标

在现有单 Runner、PostgreSQL 队列和脱敏报告基础上，先交付一条可验证的故障定位链：

`traceId → audit event → project/run/step reference`，并能从平台 API 判断 Runner 最近是否在线。

## 本首片范围

- 新增 PostgreSQL 审计事件表和查询接口，记录登录成功、密钥变更、运行创建/取消、Webhook 投递和危险确认等已进入服务层的动作；事件元数据统一脱敏，不保存密钥明文。
- 新增 Runner 状态表、内部心跳接口和状态查询接口；在线判定使用最近心跳时间，不引入 Redis、租约、fencing 或多 Runner 调度。
- 让 Runner 常驻入口按轮询周期向 Platform API 发送最小心跳，携带 Runner 标识、JMeter 版本、当前运行 ID 和队列深度。
- 补充中文运维文档和不包含真实凭据的 PostgreSQL 备份/恢复命令模板；本首片只验证 schema/资产/报告恢复路径，不把 MinIO、对象归档或生产恢复演练伪装为已完成。

## 明确不做

- 不新增 RBAC、Kafka、Redis、Kubernetes、多 Runner 租约或复杂日志采集平台。
- 不把 JMX/JTL/Runner 工作目录复制到数据库；诊断文件和 MinIO 归档仍是后续切片。
- 不在审计事件中记录请求正文、响应正文、密钥值、Cookie 或模型密钥。

## 实施步骤

1. [x] 先写 `AuditServiceTest`、`RunnerStatusServiceTest`，证明脱敏、引用字段和在线超时语义。
2. [x] 增加 Flyway V14、Repository、Service、Controller 与真实 PostgreSQL 集成测试。
3. [x] 为认证成功、Secret/Run/AI Patch/Webhook 关键成功动作接入审计；失败动作和全量 HTTP 访问日志仍列为后续补齐项。
4. [x] 增加 Runner 心跳客户端和 `RunnerApplication` 轮询调用，补 Runner 单元测试与平台内部接口鉴权测试。
5. [x] 更新中文运维文档、备份/恢复脚本模板和进度账本。
6. [x] 运行平台/Runner 定向测试，再运行根目录一键门禁并记录最终计数。

## 验收证据

- 审计事件查询能按项目、动作和 `traceId` 返回脱敏记录，并能关联 `runId/stepId`。
- 心跳新鲜时状态为 `ONLINE`，超过超时窗口为 `OFFLINE`；错误 Token 不得写入状态。
- Runner 心跳请求不包含数据库密码、回调 Token 或密钥值。
- PostgreSQL 迁移幂等；备份/恢复文档不含真实密钥；本轮所有命令退出码和未完成边界写入 `progress.md`。
