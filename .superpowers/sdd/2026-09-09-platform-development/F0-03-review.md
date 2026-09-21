# F0-03 独立复审报告

## 1. 最终判定

| 项目 | 结论 |
| --- | --- |
| F0-03 规范符合性 | **FAIL** |
| 审查决定 | **REJECT** |
| 是否允许进入 F1-01 | **否** |

当前版本集中和正常路径构建均已转绿，但 F0-03 的核心交付物还不能作为后续 30 项任务的可靠公共门禁：`.gitignore` 会静默误伤源码/夹具，一键 PowerShell 门禁会继承调用者旧的非零退出码并误报失败，根 README 的 Markdown 标记被反斜杠转义而无法按预期渲染。合同脚本仍对这三类问题给出 PASS，因此本轮不能批准，也不能进入依赖 F0-03 的 F1-01。

## 2. 阻塞问题

### I-1：`.gitignore` 的通配规则会误伤生产源码和测试夹具

- 位置：`.gitignore:16-25`，重点为 `**/runs/`、`**/reports/`、`**/data/` 和未锚定的 `data/`。
- 真实语义验证中，以下路径均被当前根 `.gitignore` 忽略：
  - `platform-api/src/main/resources/data/seed.json`
  - `runner-app/src/test/resources/data/fixture.json`
  - `web/src/data/model.ts`
  - `docs/data/schema.md`
  - `platform-api/src/main/java/com/autotest/reports/ReportService.java`
- `platform-api/src/main/resources/db/migration/V1__init.sql` 当前未被忽略，说明 Flyway 标准迁移目录暂未误伤；但资源 `data`、测试夹具和未来 `reports`/`runs` 领域源码仍存在直接丢失跟踪的风险。
- 合同脚本仅在 `scripts/Test-F0-03Contract.ps1:62-66` 搜索规则字符串是否存在，没有验证 Git ignore 的实际匹配结果，也没有负向夹具，因此未发现过宽规则。
- 最小修复要求：把运行产物规则锚定到仓库根或实际生成目录，并增加“应忽略产物、不得忽略源码/迁移/夹具”的双向合同用例。

### I-2：一键门禁继承旧 `$LASTEXITCODE`，干净配置也会误报失败

- 位置：`scripts/verify-local.ps1:15-20`。
- `Invoke-Gate` 执行脚本块后直接读取 `$LASTEXITCODE`；第一个合同检查是 PowerShell 脚本，成功时不会重置调用者先前由原生命令留下的退出码。
- 可重复命令：

~~~powershell
cmd /c exit 23
.\scripts\verify-local.ps1
~~~

实际输出先显示 `F0-03 合同检查通过。`，随即报 `门禁失败：F0-03 配置合同检查（退出码 23）`，进程退出码为 1，Maven 尚未执行。这与 README 宣称的可直接在当前 Windows PowerShell 会话运行不一致。
- 反向注入 Maven 退出码 17 时，脚本确实立即中止且总体退出非零，说明真实失败的 fail-fast 路径有效；问题是每个门禁执行前没有隔离/重置旧原生退出状态。
- 最小修复要求：每个门禁独立获取本次命令的结果，不能把调用前的 `$LASTEXITCODE` 当成本次结果；为“前置原生命令失败、合同脚本成功”的场景增加回归检查。

### I-3：根 README 的 Markdown 行内代码与代码围栏全部被错误转义

- 位置：`README.md:3,10,21-26,32-35,43-45,49-55,59-63`。
- 文件实际写入了 `\`pytest-auto-api\``、`\`web\``、`\`\`\`powershell`、`\`\`\`bash` 等字面量。反斜杠转义会让反引号作为普通字符显示，命令段不会形成 Markdown 代码围栏。
- README 的中文语义、F0-01/F0-02 状态和命令文本基本准确，但交付文档渲染损坏，不符合 F0-03 整理中文工程说明的质量要求。
- `scripts/Test-F0-03Contract.ps1:68-76` 只检查关键词和命令字符串，不检查 Markdown 围栏，因此错误 README 仍被判为通过。
- 最小修复要求：移除这些反斜杠，恢复标准行内代码和 fenced code block，并补一个能拒绝 `\`\`\`\`` 这类字面量转义的合同检查。

## 3. 已确认通过的部分

- Java 版本口径：根 `pom.xml` 固定 Java 17，并集中声明 Spring Boot 3.4.3、JUnit 5.11.4、Jackson 2.18.3、ArchUnit 1.3.0；当前三个子模块均通过根属性/BOM引用，未发现同一依赖的冲突硬编码。
- 前端版本口径：`web/package.json` 的 Vue、Vite、Vitest、TypeScript、`@vitejs/plugin-vue`、`vue-tsc` 均为精确版本，不含 `^`、`~` 或 `latest`。解析 lockfile v3 后，六项依赖在 package 声明、lock 根声明和已锁定包版本三处逐项一致。
- 部署说明：`deployment/README.md` 已用中文准确说明 JMeter 5.6.3、腾讯镜像仅做传输加速、Apache 官方 SHA-512 为信任边界，未保留空 Compose 占位描述。
- 当前快照未发现 Flyway/PostgreSQL 生产依赖、迁移目录或 Spring Boot 启动实现；除既有 Web 原型展示文字外，未看到 F1-01 的实现落地。由于目录本身不是 Git 仓库，这只能证明当前快照，不能用差异历史证明 F0-03 实施期间从未修改越界文件。

## 4. 新鲜验证证据

本轮没有构建或下载 Docker 镜像，只执行配置解析。

| 验证 | 结果 |
| --- | --- |
| `pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/Test-F0-03Contract.ps1` | 退出码 0；合同脚本自报通过，但存在上述覆盖盲区。 |
| `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-local.ps1` | 干净进程退出码 0；内部依次执行以下所有门禁。 |
| `mvn.cmd clean test` | 退出码 0；shared-contracts 16、platform-api 2、runner-app 7，共 25 项测试通过。 |
| `npm.cmd --prefix web test -- --run` | 退出码 0；1 个测试文件、10 项测试通过。 |
| `npm.cmd --prefix web run typecheck` | 退出码 0。 |
| `npm.cmd --prefix web run build` | 退出码 0；Vite 6.4.3 构建成功。 |
| `wsl.exe -d Ubuntu -- docker compose -f deployment/docker-compose.yml config` | 退出码 0；构建上下文正确解析为 `/mnt/d/codexWorkSpec/autotest/runner-app`。 |
| 前置 `$LASTEXITCODE=23` 后运行一键门禁 | 退出码 1；合同成功却被误报为退出码 23，稳定复现 I-2。 |
| Git ignore 正反例检查 | `data`/`reports` 源码与夹具被忽略；`db/migration` 未忽略，稳定复现 I-1。 |

一键门禁已经真实执行 Maven、前端测试、类型检查、构建和 WSL Compose 配置检查，因此未再重复运行同一批命令。

## 5. 证据限制与整改门槛

- 当前 `D:\codexWorkSpec\autotest` 不是 Git 仓库，无法独立核验 `F0-03-report.md` 所述“先红后绿”时序、具体变更集或“未新增生产依赖”的历史差异；本报告只以当前文件和本轮实测为依据。
- `F0-03-report.md:7,71-83` 的 PASS 结论未覆盖本报告复现的问题，修复后应同步更正实施报告并重新执行合同与一键门禁。
- 重新申请复审前至少需要：收窄 `.gitignore` 并补负向夹具；修复旧 `$LASTEXITCODE` 污染并补回归；修复 README Markdown；让合同脚本能对上述缺陷转红。完成并提供新鲜证据前，不允许进入 F1-01。
