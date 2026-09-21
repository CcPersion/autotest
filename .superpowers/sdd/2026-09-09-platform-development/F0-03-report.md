# F0-03 实施报告：工程配置与基础门禁

## 1. 范围与结论

本轮只整理工程配置、版本口径、忽略规则、中文说明和本地门禁，没有新增业务 API、页面、数据库或生产依赖，也没有开始 F1-01。

任务结论：PASS（本任务门禁通过）。

这不表示整个平台已经可用。当前 Web 仍是本地模拟数据的页面原型，平台业务闭环、持久化 API、JMeter 计划转换、Runner 队列和完整报告仍属于后续任务。

## 2. TDD 红灯证据

先新增 scripts/Test-F0-03Contract.ps1，合同检查覆盖版本固定、lockfile 一致、忽略规则、README、部署说明和一键门禁入口；实现配置前执行：

~~~text
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/Test-F0-03Contract.ps1
~~~

退出码为 1。失败报告 29 项陈旧或缺失配置，包括：

- web/package.json 的直接依赖仍使用 ^；
- .gitignore 缺少 node_modules、dist、运行目录、密钥和 IDE 文件规则；
- README 缺少 F0-01/F0-02 当前状态和真实门禁命令，并保留 engine-core 的陈旧未来时描述；
- deployment/README.md 仍描述为空 Compose 占位，未说明 JMeter、腾讯云传输镜像和 Apache 官方 SHA-512；
- scripts/verify-local.ps1 不存在。

该红灯发生在配置实现前，随后按最小范围逐项转绿。

## 3. 实际修改

### 3.1 Java/Maven

- 根 pom.xml 保持 Java 17，并明确所有 Java 构建与测试版本属性集中在根 POM：
  - Spring Boot 3.4.3
  - JUnit 5.11.4
  - Jackson 2.18.3
  - ArchUnit 1.3.0
- 未新增生产依赖，未恢复旧执行引擎或复杂运行契约。

### 3.2 前端版本

web/package.json 直接依赖全部改为精确版本，未使用 ^、~ 或 latest：

| 依赖 | 固定版本 |
| --- | --- |
| Vue | 3.5.42 |
| @vitejs/plugin-vue | 5.2.4 |
| TypeScript | 5.9.3 |
| Vite | 6.4.3 |
| Vitest | 3.2.7 |
| vue-tsc | 2.2.12 |

使用本机已有缓存执行 npm.cmd --prefix web install --package-lock-only --save-exact --ignore-scripts --offline，更新 web/package-lock.json 根项目声明并保持一致；npm 审计结果为 0 vulnerabilities。

### 3.3 忽略规则

根 .gitignore 覆盖所有模块的 target/、node_modules/、dist/，Playwright 输出，运行/报告/本地数据目录，.env，证书、私钥和 keystore，以及常见 IDE/系统文件；通过 !.env.example 允许提交示例环境文件。

### 3.4 中文说明

- 根 README 更新 F0-01/F0-02 已完成、F0-03 门禁已提供、页面仍为原型和平台闭环未完成等当前边界，并给出 Windows/WSL 真实命令。
- deployment/README.md 更新为当前 JMeter 5.6.3 Runner 基线，说明腾讯云 Apache 镜像仅用于国内传输加速，发行包仍以 Apache 官方 SHA-512 校验；移除空 Compose 占位说明。

### 3.5 门禁脚本

- scripts/Test-F0-03Contract.ps1：配置合同检查。
- scripts/verify-local.ps1：Windows PowerShell 一键门禁，按顺序执行合同检查、根 Maven 测试、前端测试、类型检查、构建和 WSL Compose 配置检查；任一步非零立即退出。

## 4. 新鲜验收证据

以下命令由一键门禁在本轮新鲜执行，均退出码 0：

| 命令/门禁 | 结果 |
| --- | --- |
| pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/Test-F0-03Contract.ps1 | 通过 |
| mvn clean test | 通过；shared-contracts 16 项、platform-api 2 项、runner-app 7 项，共 25 项 |
| npm.cmd --prefix web test -- --run | 通过；10 tests passed |
| npm.cmd --prefix web run typecheck | 通过 |
| npm.cmd --prefix web run build | 通过；Vite 6.4.3 构建成功 |
| wsl -d Ubuntu -- docker compose -f deployment/docker-compose.yml config | 通过 |
| powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-local.ps1 | 通过；所有子门禁按顺序通过 |

合同检查同时验证了 package.json 与 lockfile 的直接依赖版本一致、忽略规则覆盖范围以及 README/部署说明无陈旧 F0 描述。

## 5. 未验证内容与边界

- 本轮只验证 Compose 配置解析，没有重新启动或重建 Runner 容器；F0-02 的 JMeter 5.6.3 镜像和 smoke 证据沿用上一任务报告。
- 未验证 Platform API、数据库、登录、真实页面到 JMeter 的业务闭环；这些不属于 F0-03。
- 未执行完整平台可用性声明；后续任务仍须按需求和任务清单逐项验收。

## 6. 用户后续事项

当前没有需要用户额外处理的事项。后续开发应继续使用 scripts/verify-local.ps1 作为本地基础门禁，并保持本报告所述 F0-03 范围边界。

## 7. 第 1 轮复审整改记录

依据 F0-03-review.md 的 I-1/I-2/I-3，未进入 F1，先补回归合同和探针，再修改最小实现。

### 7.1 修复前红灯

新增合同后重新执行 scripts/Test-F0-03Contract.ps1，退出码为 1，合同真实发现：

- README 中仍有反斜杠包裹的行内反引号/代码围栏；
- data、reports、runs 宽泛规则实际忽略了源码和测试夹具；
- 合同缺少一键门禁回归探针入口。

复审已提供前置 cmd /c exit 7 后合同成功仍被误判为退出码 23 的复现证据；本轮新增 scripts/Test-F0-03Gate.ps1 将该场景固化为回归探针，并同时覆盖真实失败命令的非零路径。

### 7.2 最小修复

- README 恢复真实 Markdown 行内反引号和 fenced code block；合同拒绝反斜杠转义标记。
- .gitignore 删除 data、reports、runs 等宽泛目录规则，改为根目录和 deployment/ 下明确锚定的 runtime、runs、reports、output、tmp、cache 等生成路径。
- 合同使用一个 GUID 临时 Git 探针仓库复用全部 check-ignore 检查：
  - 负向夹具确认 platform-api、runner-app、web、docs 的 data 源码/夹具、reports 领域源码和 Flyway 迁移不被忽略；
  - 正向夹具确认根/部署运行结果、私钥和 .env 被忽略，.env.example 可提交。
- 合同和门禁回归脚本在 Remove-Item -Recurse 前解析绝对路径，确认目标位于系统 Temp 子目录且不等于 Temp 根目录；不安全时拒绝删除。
- verify-local.ps1 每个门禁执行前显式将 LASTEXITCODE 重置为 0，同时结合本次命令的 $? 和退出码判断成功/失败。
- 门禁回归探针通过临时 PATH 注入返回 17 的 mvn.cmd，确认真实失败仍为非零并立即停止。

### 7.3 整改后新鲜验证

| 验证 | 结果 |
| --- | --- |
| powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Test-F0-03Contract.ps1 | 退出码 0 |
| powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Test-F0-03Gate.ps1 | 退出码 0；旧 LASTEXITCODE=7 后完整门禁成功，真实 mvn 失败探针非零 |
| powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-local.ps1 | 退出码 0 |
| Maven、前端测试/typecheck/build、WSL Compose config | 由一键门禁全部通过 |

整改后仍只证明 F0-03 工程门禁可靠，不宣称整个平台业务闭环完成。
