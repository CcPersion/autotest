# F0-03 修复第 1 轮限定复审

## 1. 最终判定

| 项目 | 结论 |
| --- | --- |
| 原 I-1/I-2/I-3 及直接回归 | **PASS** |
| 审查决定 | **APPROVE** |
| 是否允许进入 F1-01 | **是** |

本轮严格限定复核上一轮三项阻塞问题，没有扩查 F1，也没有构建或下载 Docker 镜像。三项问题均已关闭，合同、门禁回归和带前置失败状态的一键门禁均有本轮新鲜绿灯，因此允许进入依赖 F0-03 的 F1-01。该结论只表示 F0-03 工程门禁获批，不表示后续业务闭环已经实现。

## 2. 原问题关闭情况

### I-1：`.gitignore` 过宽误伤源码/夹具——已关闭

- 原有 `**/data/`、`data/`、`**/reports/`、`**/runs/` 已删除；运行产物规则改为仓库根和 `deployment/` 下的明确锚定目录。
- 使用 Git 的真实 `check-ignore --no-index -q` 语义独立验证：
  - 不忽略：`platform-api/src/main/resources/data/seed.json`、`runner-app/src/test/resources/data/fixture.json`、`web/src/data/model.ts`、`docs/data/schema.md`、`platform-api/src/main/java/com/autotest/reports/ReportService.java`、`platform-api/src/main/resources/db/migration/V1__init.sql`、`.env.example`，退出码均为 1。
  - 应忽略：`runtime/f0-03-run/result.jtl`、`deployment/runtime/f0-03-run/result.jtl`、`.env`、`deployment/.env`，退出码均为 0。
- `scripts/Test-F0-03Contract.ps1:100-150` 已同时固定宽泛规则拒绝、源码/迁移/夹具负向路径和运行产物/密钥正向路径，不再只检查字符串存在。

### I-2：旧 `$LASTEXITCODE` 污染及真实失败传播——已关闭

- `scripts/verify-local.ps1:15-29` 在每项门禁前把 `$global:LASTEXITCODE` 重置为 0，并结合本次命令的 `$?` 与退出码判断结果。
- 在同一 PowerShell 会话先执行 `cmd.exe /c exit 23`，再运行 `scripts/verify-local.ps1`：合同、Maven、前端测试、类型检查、构建和 WSL Compose 配置检查全部继续执行并通过，最终 `PREEXISTING_23_GATE_EXIT=0`。
- 独立注入返回 17 的 `mvn.cmd` 后运行一键门禁：合同通过，执行到 Maven 时立即抛出 `门禁失败：Maven 全量测试（退出码 17）`，未进入前端门禁；观测到异常且 `$LASTEXITCODE=17`。
- `scripts/Test-F0-03Gate.ps1` 已固化旧退出码成功路径和 Maven 17 失败路径，本轮自身退出码为 0。

### I-3：README Markdown 被反斜杠转义——已关闭

- `README.md` 已恢复标准行内反引号和 fenced code block，不再存在反斜杠紧邻反引号的字面量。
- 独立逐行检查结果：转义标记 0 处；`powershell`/`bash` 开围栏 4 处、闭围栏 4 处；无嵌套、孤立或未闭合围栏。
- `scripts/Test-F0-03Contract.ps1:152-162` 已增加反斜杠转义拒绝条件，防止同类回归。

## 3. 临时目录递归删除安全检查

- `scripts/Test-F0-03Contract.ps1:43-55,140-149` 与 `scripts/Test-F0-03Gate.ps1:18-30,62-73` 均在 `Remove-Item -Recurse -Force` 前：
  1. 通过 `Resolve-Path` 和 `System.IO.Path.GetFullPath` 得到绝对目标；
  2. 通过 `GetTempPath` 得到系统 Temp 绝对根；
  3. 要求目标不等于 Temp 根，且必须以 Temp 根加目录分隔符为前缀；
  4. 校验不通过时拒绝递归删除。
- 两类探针目录均由系统 Temp 下的固定任务前缀加 GUID 创建。本轮合同和门禁回归结束后，`autotest-f003-ignore-*`、`autotest-f003-gate-*` 新增残留均为 0。

## 4. 新鲜验证证据

| 验证 | 结果 |
| --- | --- |
| `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Test-F0-03Contract.ps1` | 退出码 0；Git ignore、README、门禁脚本合同通过。 |
| README 原始字符与围栏状态检查 | 退出码 0；转义标记 0，4 组代码围栏成对闭合。 |
| 独立 Git ignore 正反路径检查 | 全部符合预期；源码、迁移、夹具不忽略，根/部署运行产物与环境文件被忽略。 |
| `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Test-F0-03Gate.ps1` | 退出码 0；内部完整成功门禁通过，Maven 17 失败探针被拦截。 |
| 前置 `$LASTEXITCODE=23` 后运行 `scripts/verify-local.ps1` | 退出码 0；旧退出码未污染任何门禁。 |
| 一键门禁内 `mvn.cmd clean test` | 退出码 0；shared-contracts 16、platform-api 2、runner-app 7，共 25 项通过。 |
| 一键门禁内 `npm.cmd --prefix web test -- --run` | 退出码 0；1 个测试文件、10 项测试通过。 |
| 一键门禁内前端 typecheck/build | 均退出码 0；Vite 6.4.3 构建成功。 |
| 一键门禁内 WSL Compose config | 退出码 0；上下文解析为 `/mnt/d/codexWorkSpec/autotest/runner-app`。 |
| 独立 Maven 17 注入 | 正确失败；异常被捕获，`$LASTEXITCODE=17`，未进入后续前端门禁。 |

## 5. 边界与后续事项

- 本轮没有检查或实现 F1-01 内容，没有启动容器，也没有构建镜像。
- 限定复审范围内未发现剩余阻塞项，F0-03 可批准并允许进入 F1-01；F1-01 仍须按其自身简报、测试和独立复审完成验收。
