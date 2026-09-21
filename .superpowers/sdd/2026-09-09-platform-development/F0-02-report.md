# F0-02 实施报告：固定 JMeter 5.6.3 Runner 运行环境

## 1. 范围与结果

本轮只实现 Runner 镜像、内置 smoke JMX、Compose 基线服务和 WSL 冒烟脚本，未修改 Web、Platform API、需求/任务权威文档，也未进入 F0-03。结果为 `DONE_WITH_CONCERNS`：代码、静态契约、Maven、WSL Docker 镜像和容器冒烟均通过；JMeter 下载使用用户指定的腾讯云 Apache 镜像，完整包仍用 Apache 官方 SHA-512 校验。

## 2. TDD 红灯证据

先新增 `runner-app/src/test/java/com/autotest/runner/RunnerImageContractTest.java`，再实现生产/部署文件。

1. `mvn -pl runner-app -am "-Dtest=RunnerImageContractTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 退出码 1：4 项测试因 `runner-app/Dockerfile`、`runner-app/src/test/resources/smoke.jmx`、`deployment/docker-compose.yml` 和两个脚本不存在而报 `NoSuchFileException`。
2. 增加 Compose 常驻服务约束后，同一目标测试退出码 1：`tail -f /dev/null` 断言失败，暴露了 `command: jmeter --version` 会立即退出、healthcheck 无法成立的问题。
3. 增加下载源/无 apt/超时/非静默下载约束后目标测试退出码 1：旧 archive+apt 实现不满足腾讯云镜像和镜像自带工具约束；随后又增加手工断点下载约束，旧内建 retry 实现退出码 1。
4. 切换腾讯云源后再次先改测试，旧 dlcdn 默认值目标测试退出码 1；改为腾讯云地址后转绿。

过程中首次 BuildKit 还出现过 `rpc error: code = Unavailable desc = error reading from server: EOF`。后续实测确认 CDN 连接中断时内建 `curl --retry` 会回退 partial，因此改为同一 Docker `RUN` 内有限手工循环、`curl -C -` 断点续传和当前字节数输出；没有再用外层盲重试。

## 3. 实际新增/修改

- `runner-app/Dockerfile`
  - 固定 `eclipse-temurin:17-jre-jammy`、JMeter `5.6.3`、包大小 `87414762` 和 Apache 官方 SHA-512。
  - 默认下载地址为 `https://mirrors.cloud.tencent.com/apache/jmeter/binaries/apache-jmeter-5.6.3.tgz`，由 `JMETER_URL` ARG 覆盖；未安装 apt 包，使用基础镜像已有的 curl、tar、gzip、sha512sum。
  - 下载最多 20 次，每次 `curl -C - --max-time 300`，失败保留 partial 并打印当前字节数；完整大小后才执行 SHA-512，再解压。
  - 设置 Java headless、`/work/runs`，创建 UID 10001 的非 root `runner`，内置复制 smoke JMX；无插件、X11、桌面包。
- `runner-app/src/test/resources/smoke.jmx`：仅 TestPlan、ThreadGroup、LoopController、内置 DebugSampler；无 JSR223、BeanShell、OS Process、HTTP/JDBC 外部网络组件。
- `deployment/docker-compose.yml`：单一 `runner-app` 服务、专用 `/work/runs` volume、非 root 用户和有效 healthcheck；command 先打印 JMeter 版本再 `tail -f /dev/null` 保持基线服务常驻。
- `deployment/scripts/build-runner-image.sh`：WSL/Linux Docker 构建脚本。
- `deployment/scripts/smoke-runner.sh`：检查 UID、Java 17、JMeter 版本并运行 `jmeter -n` smoke；临时结果目录显式允许容器 UID 10001 写入。
- `runner-app/src/test/java/com/autotest/runner/RunnerImageContractTest.java`：4 项镜像、JMX、Compose、脚本合同测试。

未修改 `runner-app/pom.xml`，未新增生产依赖。

## 4. 绿灯与实机证据

- 目标测试：`mvn -pl runner-app -am "-Dtest=RunnerImageContractTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，退出码 0，4 tests / 0 failures / 0 errors。
- 全量构建：`mvn clean test`，退出码 0；shared-contracts 16 项、platform-api 2 项、runner-app 7 项测试均通过。
- WSL 构建：`wsl -d Ubuntu -- docker build --progress=plain --file runner-app/Dockerfile --tag autotest/runner:0.1.0 runner-app`，退出码 0；日志显示 `87414762/87414762`，随后 SHA-512 校验、解压和镜像导出完成。
- 构建脚本：`wsl -d Ubuntu -- bash deployment/scripts/build-runner-image.sh`，退出码 0，命中已验证构建缓存。
- 版本：`docker run --rm autotest/runner:0.1.0 jmeter --version` 明确输出 `5.6.3`。
- 运行时：`docker run --rm --entrypoint /bin/sh autotest/runner:0.1.0 -c "id -u; java -version"` 输出 UID `10001` 和 `openjdk version "17.0.20"`。
- JMX/JTL：`docker run --rm --volume /tmp/f0-02-smoke-run:/work/runs autotest/runner:0.1.0 jmeter -n -t /opt/runner/smoke.jmx -l /work/runs/result.jtl` 退出码 0；JTL 262 bytes、2 行（表头+1 条 `success=true` 样本）。
- 脚本冒烟：`RUNNER_IMAGE=autotest/runner:0.1.0 bash deployment/scripts/smoke-runner.sh` 退出码 0，并生成非空 result.jtl。
- Compose：`wsl -d Ubuntu -- docker compose -f deployment/docker-compose.yml config` 退出码 0；`up -d --wait --no-build` 显示 `Container deployment-runner-app-1 Healthy`；`ps` 显示 `Up (healthy)`；随后仅清理本任务创建的 Compose 容器、网络和 volume。
- 脚本语法：`wsl -d Ubuntu -- bash -n deployment/scripts/build-runner-image.sh deployment/scripts/smoke-runner.sh` 退出码 0。
- 旧/禁用组件引用搜索：在 Dockerfile、smoke JMX、deployment 范围搜索 `jmeter-plugins|x11|openbox|desktop|jsr223|beanshell|osprocess|httpsamplerproxy|jdbcrequest`，结果 `NO_MATCHES`。

## 5. 遗留风险与边界

- 腾讯云镜像是本机当前网络下的下载源；其包大小和内容通过 Apache 官方 SHA-512 校验，`JMETER_URL` 可覆盖为其他可达源。若部署环境禁止该镜像，需要在构建时显式传入兼容 URL 并保留同一哈希校验。
- JMeter 启动仍会输出 package scanning deprecation warning 和 Java preferences 信息，但命令、smoke 样本和 healthcheck 均成功；这不影响 F0-02 验收。
- Java Runner 队列、动态 JMX 生成、取消、结果回传不在本任务范围，留给后续 F1 任务。
