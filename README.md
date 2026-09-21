# AI 接口自动化测试平台

本项目建设团队内部使用的 AI 接口自动化测试平台。平台参考 `pytest-auto-api` 已验证的测试分层和用例思想，参考 MeterSphere 的接口调试与场景编排方式，使用 Apache JMeter 5.6.3 作为执行内核；平台资产、页面、API 和报告由本项目逐步实现。

## 当前阶段

- F0-01 已完成：旧的纯 Java 执行引擎和过度复杂的运行契约已清理，共享契约保持最小执行 DTO。
- F0-02 已完成：Runner 镜像固定 Java 17 与 Apache JMeter 5.6.3，使用非 GUI CLI、非 root 用户和官方 SHA-512 校验。
- F0-03 提供工程版本、忽略规则、中文说明和本地一键门禁。
- `web` 已完成登录、项目/环境/接口/用例工作台的第一批真实 API 接入；运行中心和报告页仍在持续接入。
- `platform-api` 已具备 PostgreSQL 迁移、登录、测试资产、运行队列、JMeter 计划结果回传等阶段一能力；完整场景编排、SSE、导出和 AI 工作台仍未完成。
- `runner-app` 已能领取运行、编译单接口 JMX、调用 JMeter 5.6.3、回传脱敏步骤结果；这不等于全部 pytest-auto-api 能力已经复刻。
- 当前 Compose 已能启动 PostgreSQL、Platform API、Web 和 Runner，并通过一次真实接口运行验收；项目仍处于 F1 阶段开发中，不能宣称为最终完整平台。

## 权威文档

- [产品需求规格说明书](docs/requirements/01-产品需求规格说明书.md)
- [开发任务清单](docs/tasks/01-开发任务清单.md)
- [页面原型说明](docs/prototype/01-页面原型说明.md)

## 目录结构

- `web`：Vue 3 + TypeScript 页面与静态镜像构建。
- `platform-api`：Spring Boot 平台控制 API、资产和运行报告接口。
- `runner-app`：JMX 生成、JMeter 子进程和结果适配服务。
- `shared-contracts`：Platform API 与 Runner 共享的最小 DTO。
- `deployment`：WSL Docker Compose 和 Runner 镜像相关脚本。
- `scripts`：本地工程合同检查和一键验证入口。

## 页面原型

在 Windows PowerShell 中从仓库根目录安装并启动：

```powershell
npm.cmd --prefix web ci
npm.cmd --prefix web run dev -- --host 127.0.0.1
```

浏览器访问 <http://127.0.0.1:5173/>。登录、项目/环境、接口/用例和运行报告页面已接入阶段一 API；场景、AI 和运营页面仍保留原型交互，不代表全部需求已经完成。

## 本地验证

推荐在 Windows PowerShell 中执行一键门禁；命令按顺序执行，任一步失败都会立即退出：

```powershell
.\scripts\verify-local.ps1
```

需要单独执行时，可使用以下真实命令：

```powershell
wsl -d Ubuntu -- bash -lc "cd /mnt/d/codexWorkSpec/autotest && exec mvn clean test"
npm.cmd --prefix web test -- --run
npm.cmd --prefix web run typecheck
npm.cmd --prefix web run build
wsl -d Ubuntu -- docker compose --env-file deployment/.env -f deployment/docker-compose.yml config
```

首次启动完整本地服务前，先复制并填写部署配置：

```powershell
Copy-Item deployment/.env.example deployment/.env
# 编辑 deployment/.env，替换所有“请替换”值
.\deployment\scripts\start-platform.ps1
```

如果当前仓库尚未提供 PowerShell 包装脚本，可在 WSL 中执行：

```bash
cp deployment/.env.example deployment/.env
bash deployment/scripts/start-platform.sh
```

阶段一真实浏览器、三服务重启和报告恢复验收：

```powershell
$env:E2E_ADMIN_PASSWORD = '填写 deployment/.env 中的管理员密码'
.\deployment\scripts\verify-platform-flow.ps1
```

依赖版本在根 `pom.xml`、`web/package.json` 和 `web/package-lock.json` 中固定；不使用 `^`、`~` 或 `latest` 漂移。F0-02 Runner 镜像允许使用腾讯云 Apache 镜像加速传输，但仍必须通过 Apache 官方 SHA-512 校验。
