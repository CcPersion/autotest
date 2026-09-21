# F0-02 独立复审报告

## 1. 复审结论

- 任务结论：**PASS**
- 变更决策：**APPROVE**
- F0-03 准入：**允许进入 F0-03**

现有实现满足 F0-02 的任务边界和验收条件：镜像固定 JMeter 5.6.3，下载后以指定的 Apache 官方 SHA-512 校验再解压；运行时为 Java 17、UID 10001 非 root、Java headless；smoke 仅使用 JMeter 内置组件并以 `-n` 执行；JTL 实测包含一条 `success=true` 样本；Compose 配置有效且服务可在不重建镜像的情况下进入 healthy。未发现阻止 F0-03 的问题。

## 2. 复审范围

本次以以下文件为权威输入：

- `docs/requirements/01-产品需求规格说明书.md`
- `docs/tasks/01-开发任务清单.md` 的 F0-02
- `.superpowers/sdd/2026-09-09-platform-development/F0-02-brief.md`
- `.superpowers/sdd/2026-09-09-platform-development/F0-02-report.md`

只读审阅了 `runner-app/Dockerfile`、smoke JMX、`deployment/docker-compose.yml`、两个部署脚本、`RunnerImageContractTest` 和 `runner-app/pom.xml`。未修改生产代码，未重建或下载镜像，未开始 F0-03。

## 3. 静态审阅结果

### 3.1 版本、下载和完整性

- `JMETER_VERSION=5.6.3`，归档名固定为 `apache-jmeter-5.6.3.tgz`。
- 默认下载地址是腾讯云 Apache 镜像；这是国内镜像源，不改变内容信任边界。
- SHA-512 为简报固定的官方值 `5978a1a3...e689083`，且先检查完整字节数，再执行 `sha512sum --check --status`，通过后才解压。国内镜像只承担传输，完整性由固定官方哈希控制。
- 下载循环由 `max_attempts=20` 限定；每次有 15 秒连接超时和 300 秒总超时，使用 `curl -C -` 从已保留的 partial 断点续传。失败最终由 `.complete`、包大小和 SHA-512 三道门禁关闭，不会解压不完整或被替换的归档。

### 3.2 非 GUI、插件和权限边界

- 镜像未执行 `apt-get install`，未安装 X11、桌面环境、插件管理器或第三方 JMeter 插件。
- `/opt/jmeter/lib/ext` 运行时清单只看到 JMeter 官方发行包自带的 `ApacheJMeter_*.jar`；未发现第三方扩展 Jar。
- `JAVA_TOOL_OPTIONS=-Djava.awt.headless=true`；实际测试执行脚本明确使用 `jmeter -n`。
- 镜像用户为 `runner`，UID 固定为 10001，工作目录为 `/work/runs`；Compose 同样固定 `10001:10001` 并使用独立 volume。
- smoke JMX 只包含 TestPlan、ThreadGroup、LoopController 和内置 DebugSampler；没有 JSR223、BeanShell、OS Process、HTTP/JDBC 外部网络组件。

### 3.3 Compose、脚本和测试

- Compose 单独声明 `runner-app`、专用运行卷和 `jmeter --version` healthcheck；常驻命令先验证 JMeter 版本，再保持基线容器存活，符合 F0-02 尚未实现 Java Runner 任务循环的边界。
- 构建脚本只构建指定 Dockerfile；smoke 脚本检查非 root、Java 17、JMeter 版本并执行 `jmeter -n`。
- 4 项镜像合同测试覆盖版本/哈希/有限续传、headless/非 root、JMX 禁止组件、Compose 和脚本入口；本轮 Maven 实测通过。

## 4. 新鲜验证证据

本轮复审只使用已存在的 `autotest/runner:0.1.0`，没有重复执行 `docker build`。

1. `docker run --rm autotest/runner:0.1.0 jmeter --version`
   - 退出码 0；明确输出 `5.6.3`。
2. `docker run --rm --entrypoint id autotest/runner:0.1.0 -u`
   - 退出码 0；输出 `10001`。
3. `docker run --rm --entrypoint java autotest/runner:0.1.0 -version`
   - 退出码 0；输出 Temurin OpenJDK `17.0.20`。
4. `docker run --rm --entrypoint printenv autotest/runner:0.1.0 JAVA_TOOL_OPTIONS`
   - 退出码 0；输出 `-Djava.awt.headless=true`。
5. `docker run --rm --volume <本轮临时卷>:/work/runs autotest/runner:0.1.0 jmeter -n -t /opt/runner/smoke.jmx -l /work/runs/result.jtl`
   - 退出码 0；JMeter 汇总为 1 个样本、0 个错误。
   - JTL 共 2 行（表头加 1 个样本），样本行为 `Built-in smoke sampler,200,OK,...,true`。
6. `docker compose -p f002reviewca3aebe7 -f deployment/docker-compose.yml config`
   - 退出码 0。
7. `docker compose -p f002reviewca3aebe7 -f deployment/docker-compose.yml up -d --wait --no-build`
   - 退出码 0；`runner-app` 为 `Up (healthy)`。
8. `bash -n deployment/scripts/build-runner-image.sh deployment/scripts/smoke-runner.sh`
   - 退出码 0。
9. `mvn clean test`
   - 退出码 0，`BUILD SUCCESS`。
   - `shared-contracts` 16 项、`platform-api` 2 项、`runner-app` 7 项测试均为 0 failures、0 errors、0 skipped。

运行后已执行带同一唯一 project name 的 `docker compose down --volumes --remove-orphans`，并删除本轮 smoke 临时卷；两项清理退出码均为 0。本轮未删除或影响其他容器、网络和卷。

## 5. 非阻塞观察与后续边界

- `deployment/README.md` 仍写着“Task 1 空服务配置占位”，与当前已有 runner-app Compose 服务不一致。它不属于 F0-02 明示验收项，不阻塞本任务；建议在 F0-03 统一工程说明时修正。
- 当前镜像以 Java 17 大版本和 JMeter 发行包哈希为基线，基础镜像 tag 未固定到 digest。F0-02 没有要求固定基础镜像 digest，因此不阻塞；若后续要求字节级可复现构建，可另立供应链加固任务处理。
- 产品级只读根文件系统、依赖漏洞扫描、Java Runner 队列/JMX 生成/取消/结果回传均不在 F0-02 的验收范围，本报告不把它们视为已完成。

## 6. 最终判定

F0-02 的实现和本轮新鲜实机证据一致，关键安全/运行门禁均关闭：**PASS / APPROVE / 允许 F0-03**。
