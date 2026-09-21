# F1-10 隔离 WSL 端到端验收入口实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 提供一个不读取 `deployment/.env`、不依赖 Windows `npm.cmd`、使用随机 Compose project/回环端口/专属卷的 WSL 一键 F1-10 验收入口，并保留可审查的中文报告与清理证据。

**Architecture:** `scripts/f1-10-wsl-e2e.sh` 在 WSL 内生成仅属于本次运行的 env、Compose 文件和独立 Nginx mock，使用 WSL Maven、Docker Compose 和容器内 Web 构建；PowerShell 仅把仓库路径转换为 WSL 路径并调用 Bash。首次 Playwright spec 负责真实资产和运行，服务重启后恢复 spec 重新登录并通过同一 `projectId/runId` 查看报告；脚本统一捕获命令、健康、脱敏日志、截图/trace 和清理快照，失败也执行精确 `down --volumes --remove-orphans`。

**Tech Stack:** Bash/WSL, Docker Compose, PostgreSQL 16, Nginx mock, Java 17 Maven artifacts, Playwright Chromium, Python 3 标准库辅助 JSON/扫描。

**Spec:** `docs/tasks/01-开发任务清单.md` F1-10；`.superpowers/sdd/2026-09-09-platform-development/F1-10-brief.md`。

## Global Constraints

- 不读取、修改或删除 `deployment/.env`、既有 Compose project、既有卷或固定容器名。
- 不扩展 F2、旧 F4-05 或生产公共 API；生产容器只使用既有 Platform/Runner/Web 入口。
- Playwright 通过 Platform API 创建项目、环境、JSON 接口定义/用例并由真实 Runner/JMeter 运行；mock 目标必须是独立服务。
- 证据中密码、回调 Token、主密钥、动态 Authorization/CSRF 和 mock sentinel 必须脱敏；任何泄漏、健康失败、状态门禁失败或清理残留都以非零退出。
- 失败、取消、JMeter 非零和 Runner 重启终态通过既有真实 Runner 测试命令纳入报告，不伪造产品运行结果。

---

### Task 1: 新入口的红灯合同测试

**Files:**
- Create: `scripts/test-f1-10-wsl-e2e.sh`
- Test target: `scripts/f1-10-wsl-e2e.sh`, `scripts/Test-F1-10E2E.ps1`

- [ ] **Step 1: Write the failing static/behavior contract test**

检查 Bash 主脚本、PowerShell 薄包装和恢复 spec 的关键约束：不出现 `deployment/.env`、`npm.cmd`、固定容器名；包含随机 project、随机 localhost ports、生成临时 env/compose、`down --volumes --remove-orphans`、资源/进程清零、mock target、Runner status、F1-10 report 和 sentinel 扫描。

- [ ] **Step 2: Run the contract test to verify it fails**

Run: `wsl.exe -d Ubuntu -- bash -lc "cd /mnt/d/codexWorkSpec/autotest && bash scripts/test-f1-10-wsl-e2e.sh"`

Expected: FAIL because the new files do not exist.

### Task 2: Bash 主入口和证据/清理实现

**Files:**
- Create: `scripts/f1-10-wsl-e2e.sh`
- Create: `scripts/Test-F1-10E2E.ps1`

- [ ] **Step 1: Implement isolated preflight and generated resources**

在 Bash 中用 `mktemp -d` 建立中文时间戳证据目录，生成 `autotest-f110-<random>` project、UUID Runner ID、随机可用 loopback ports、临时 env、Compose YAML 和 Nginx target config；所有路径只落在证据目录，Compose 只声明匿名 project-scoped volumes，不声明 `container_name`。

- [ ] **Step 2: Implement run/restart/report gates**

记录每条命令和退出码；启动前仅检查当前 project 的资源，启动 PostgreSQL、mock、Platform、Runner、Web；健康检查 API/Web/DB，并登录后查询 `/api/v1/runners/status`，要求同一 `runnerId` 为 `ONLINE` 且 `jmeterVersion=5.6.3`。执行首次 Playwright spec，保存 project/run 元数据、截图和 trace；仅重启本 project 的 Platform/Web/Runner，重新健康检查并执行恢复 spec，要求同一 runId 报告 `PASSED` 且包含响应正文和断言证据。

- [ ] **Step 3: Implement fail-closed evidence scan and exact cleanup**

对证据目录做密码、Token、主密钥、mock sentinel、Authorization/CSRF 文本扫描；扫描命中时只保留脱敏后的证据并置失败。无论成功/失败都执行同一 project 的 `docker compose down --volumes --remove-orphans`，再枚举 project 容器、网络、卷和脚本派生进程，非空即失败；生成 `F1-10-report.md`，写入命令、退出码、健康快照、runId、重启前后报告、脱敏扫描和清理证据。

- [ ] **Step 4: Implement thin Windows wrapper**

PowerShell 只解析仓库目录、转成 `/mnt/<drive>/...`，调用 `wsl.exe -d Ubuntu -- bash <script>`；不调用 `npm.cmd`、不读取 `.env`、不启动/停止旧 project。

### Task 3: 真实浏览器恢复场景

**Files:**
- Modify: `web/e2e/f1-10-platform-flow.spec.ts`
- Create: `web/e2e/f1-10-report-recovery.spec.ts`

- [ ] **Step 1: Extend first spec evidence**

首次流程使用独立 mock base URL，创建 JSON GET 定义和用例，使用状态码 + JSON_PATH 断言，保存 `projectId/runId`，在响应 tab、断言 tab 和 report API response 中检查 mock response body、断言状态和无 sentinel。

- [ ] **Step 2: Add post-restart browser recovery**

恢复 spec 重新登录，读取脚本提供的 metadata，选择同一项目，通过浏览器上下文请求同一 report API 并在页面报告面板展示/断言同一 runId、`PASSED`、响应正文和断言证据；保存恢复截图/trace，不创建第二个运行。

- [ ] **Step 3: Run Playwright list/type checks before Compose**

Run: `npm.cmd --prefix web exec playwright test e2e/f1-10-platform-flow.spec.ts e2e/f1-10-report-recovery.spec.ts -- --list`

Expected: both specs are discovered; actual execution is performed only by the WSL entrypoint against its generated Compose environment.

### Task 4: 定向验证和交付报告

**Files:**
- Modify: `scripts/test-f1-10-wsl-e2e.sh` only if a gate fails.

- [ ] **Step 1: Run Bash contract and shell syntax tests**

Run: `bash -n scripts/f1-10-wsl-e2e.sh scripts/test-f1-10-wsl-e2e.sh`; then the WSL contract command from Task 1.

- [ ] **Step 2: Run direct Runner failure/cancel/nonzero/restart tests**

Run the existing WSL Maven Runner test selection covering `RunnerWorkerTest`, `JmeterProcessRunnerTest` and `RunnerApplicationTest`; record exact counts and include them as F1-10 gate evidence without claiming these unit tests replace the browser flow.

- [ ] **Step 3: Run the real one-command WSL gate**

Run: `wsl.exe -d Ubuntu -- bash -lc "cd /mnt/d/codexWorkSpec/autotest && bash scripts/f1-10-wsl-e2e.sh"`; require exit code 0, unique Chinese report directory, two browser phases, restart recovery, zero leaks and zero resource/process leftovers.

- [ ] **Step 4: Report review handoff**

Return modified files, red/green test evidence, exact WSL/Docker/Playwright commands and exit codes, report path, project/run IDs, cleanup proof, and unverified boundaries. Hand off to Sol; do not declare final approval.
