# 运维、审计与恢复说明

## 当前首片能力

- Platform API 记录登录成功、密钥变更、AI Patch 确认、运行创建/取消和 Webhook 投递等已接入动作。
- 审计查询：登录后访问 `/api/v1/projects/{projectId}/audit-events`，可按 `action`、`traceId` 查询；事件正文只保留脱敏元数据。
- Runner 每个轮询周期向 `/api/v1/internal/runners/{runnerId}/heartbeat` 发送版本、JMeter 版本和队列深度；登录后访问 `/api/v1/runners/status` 查看 `ONLINE/OFFLINE`。
- `ONLINE` 表示最近 30 秒收到心跳；超过窗口显示 `OFFLINE`，不代表历史运行数据丢失。

## PostgreSQL 备份与恢复

`deployment/scripts/backup-postgres.sh` 和同名 PowerShell 包装脚本不会打印数据库密码，备份文件按 0600 创建：

~~~bash
cp deployment/.env.example deployment/.env
# 仅在本机填写 deployment/.env，不要提交 deployment/.env
bash deployment/scripts/backup-postgres.sh backups
~~~

恢复是覆盖性操作，必须显式确认；先停止写入流量并确认备份文件来自可信来源：

~~~bash
bash deployment/scripts/restore-postgres.sh --confirm backups/autotest-<时间>.dump
~~~

Windows 可使用：

~~~powershell
.\deployment\scripts\backup-postgres.ps1 -OutputDirectory backups
.\deployment\scripts\restore-postgres.ps1 -BackupFile backups\autotest-<时间>.dump -ConfirmRestore
~~~

恢复后至少执行 `docker compose ... ps`、`/actuator/health`、登录、资产列表和历史报告抽查。恢复脚本本身不自动删除卷、不自动重启服务，也不会恢复 Runner 工作目录。

## 未完成边界

- 当前 Compose 没有 MinIO；对象附件、JMX/JTL/日志归档和 MinIO 备份/恢复仍未交付。
- 尚未把生产备份上传到远端对象存储，也未宣称完成灾备演练；本轮只提供可审阅、可手工执行的 PostgreSQL 脚本和本地 schema/数据恢复路径。
- 审计事件不会记录密钥明文、请求/响应正文或模型密钥；登录失败审计和全量 HTTP 访问日志仍需后续按需求补齐。
