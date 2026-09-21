# F0-02 实施简报：固定 JMeter 5.6.3 Runner 运行环境

## 目标

在 WSL2 的 Docker 环境中得到可重复、非 GUI、非 root 的 JMeter 5.6.3 命令行运行环境，为后续 Java Runner 生成并执行 JMX 提供唯一基线。

## 修改范围

- `runner-app/Dockerfile`
- `runner-app/pom.xml`（只有测试确有必要时修改）
- `runner-app/src/test/` 下的镜像合同测试与最小 `smoke.jmx`
- `deployment/docker-compose.yml`
- `deployment/scripts/` 下的 Runner 镜像构建/冒烟脚本
- 本任务必要的中文说明

不得修改 Web、Platform API、需求和任务权威文档；不得开始 F0-03；不得引入第三方 JMeter 插件、GUI 桌面依赖或额外生产 Java 依赖。

## 固定输入

- JMeter：`5.6.3`
- 官方归档：`https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-5.6.3.tgz`
- 官方 SHA-512：`5978a1a35edb5a7d428e270564ff49d2b1b257a65e17a759d259a9283fc17093e522fe46f474a043864aea6910683486340706d745fcdf3db1505fd71e689083`
- Java：17
- 容器用户：非 root
- 运行目录：`/work/runs`

## TDD 与验收

1. 先增加静态镜像合同/夹具测试并证明红灯，再写 Dockerfile、Compose 和脚本。
2. 下载发行包后必须执行 SHA-512 校验再解压。
3. 镜像不得安装插件管理器、第三方插件、X11/桌面包；JMeter 始终以 `-n` 非 GUI 模式执行，Java 设为 headless。
4. 最小 JMX 只能使用 JMeter 内置组件，不使用 JSR223、BeanShell、OS Process 或网络外部服务。
5. 新鲜验证：
   - `mvn clean test`
   - `wsl -d Ubuntu -- docker build ...`
   - 容器内 `jmeter --version` 明确输出 5.6.3
   - `jmeter -n -t smoke.jmx -l result.jtl` 退出码 0，JTL 存在且有样本记录
   - `docker compose config` 通过
   - 运行时 `id -u` 非 0，`java -version` 为 17
6. 所有新增说明性文字使用中文；报告写入 `F0-02-report.md`，包括红灯、绿灯、命令、退出码和未验证项。

## 设计边界

本任务只固定 Runner 的 JMeter CLI 基线。Java Runner 的队列领取、JMX 生成、子进程生命周期、取消和结果回传属于 F1-07 及后续任务，不在本任务实现。
