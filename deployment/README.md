# 部署基线

当前部署目录承载固定 JMeter Runner 基线，并在构建时把可执行的 Runner fat JAR 注入镜像。Compose 已连接 PostgreSQL、Platform API、Web 和 Runner，可用于本地阶段一验收；完整业务能力仍按 `docs/tasks` 逐项交付。

## Runner 版本与安全边界

- Runner 镜像固定 Java 17 和 Apache JMeter 5.6.3。
- JMeter 使用非 GUI CLI 模式执行，容器使用非 root 用户和独立 /work/runs 运行目录。
- 构建脚本默认使用腾讯云 Apache 镜像传输发行包，以适应国内网络；镜像只负责传输加速，不改变制品信任边界。
- 解压前必须使用 Apache 官方发布的 SHA-512 值校验完整发行包；下载大小、哈希或解压任一步失败都应终止构建。
- `deployment/scripts/build-runner-image.sh` 会先使用 Maven 国内镜像构建 `runner-app` fat JAR，再构建镜像；Compose 通过 `java -jar /opt/runner/runner.jar` 启动常驻 Runner。
- 不安装未经审核的 JMeter 插件、桌面环境或 GUI 依赖。

## WSL 命令

在仓库根目录执行：

~~~bash
# 首次使用：复制配置模板并替换所有“请替换”值
cp deployment/.env.example deployment/.env

# 检查 Compose 解析结果，不启动服务
docker compose --env-file deployment/.env -f deployment/docker-compose.yml config

# 构建 Platform API、Runner fat JAR、Web 静态资源并启动完整本地服务
bash deployment/scripts/start-platform.sh

# Windows PowerShell：真实浏览器闭环、三服务重启和报告恢复验收
$env:E2E_ADMIN_PASSWORD='填写 deployment/.env 中的管理员密码'
.\deployment\scripts\verify-platform-flow.ps1

# 可选：验收测试集合成员选择及重启后集合报告元数据
.\deployment\scripts\verify-platform-flow.ps1 -SpecPath e2e/f2-10-suite.spec.ts -ExpectSuiteReport

# 可选：复杂业务集合验收（仅测试覆盖层，不改变基础部署拓扑）
# 先用独立的测试 Compose 项目启动 deployment/docker-compose.f2-10-complex.yml，
# 再将 E2E_BASE_URL 指向该项目的 Web 端口并执行：
npm.cmd --prefix web exec playwright test e2e/f2-10-complex-suite.spec.ts --workers=1

# 构建固定版本 Runner 镜像
bash deployment/scripts/build-runner-image.sh

# 运行 JMeter 内置 smoke 计划
RUNNER_IMAGE=autotest/runner:0.1.0 bash deployment/scripts/smoke-runner.sh

# PostgreSQL 备份（恢复命令和 MinIO 边界见中文运维说明）
bash deployment/scripts/backup-postgres.sh backups
~~~

Windows PowerShell 可通过 WSL 调用同一检查：

~~~powershell
wsl -d Ubuntu -- docker compose --env-file deployment/.env -f deployment/docker-compose.yml config
~~~

Windows PowerShell 也可以直接执行 `.\deployment\scripts\start-platform.ps1`，该脚本会调用同一份 WSL 启动流程。

完整服务启动依赖 `deployment/.env` 中的本机密钥；Compose 会启动 PostgreSQL、Platform API、Web 和 Runner，并等待健康检查。已验证一次真实 API→Runner→JMeter→报告闭环；阶段一浏览器端到端和完整功能验收仍未完成，不能把“服务健康”单独当作 F1-10 已通过。

审计、Runner 状态、备份和恢复说明见 `deployment/README-运维恢复.md`。恢复是覆盖性操作，必须显式确认；不要把 `deployment/.env` 或备份文件提交到仓库。
