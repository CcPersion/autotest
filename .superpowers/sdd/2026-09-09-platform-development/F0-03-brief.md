# F0-03 实施简报：工程配置与基础门禁

## 目标

形成后续 30 项任务共同使用的一键本地验证入口，统一后端和前端版本口径、忽略规则与中文工程说明。只整理工程门禁，不实现任何业务功能。

## 修改范围

- 根 `pom.xml`
- `web/package.json` 与必要的 lockfile 更新
- 根 `.gitignore`
- 根 `README.md`、`deployment/README.md`
- 根 `scripts/` 下的一键验证脚本与必要的配置合同测试
- 本任务执行报告

不得修改需求/任务权威文档，不得新增业务 API、页面、数据库、生产依赖或开始 F1-01。

## 实施要求

1. Java 固定为 17；Spring Boot、JUnit、Jackson、ArchUnit、Vue、TypeScript、Vite、Vitest、vue-tsc 的版本由唯一清晰位置管理，不保留无效或互相冲突的声明。
2. 前端依赖不得使用 `^`、`~` 或 `latest` 漂移；lockfile 与 package.json 一致。
3. `.gitignore` 至少排除所有模块 `target/`、`node_modules/`、`dist/`、Playwright 临时输出、运行目录、本地数据目录、`.env`、证书/私钥/keystore 和常见 IDE/系统文件；允许提交明确的 `.env.example`。
4. 根 README 使用中文准确描述当前状态：F0-01/F0-02 已完成，页面仍是原型，平台业务闭环尚未完成；给出 Windows/WSL 的真实命令，不再写“第一项任务将删除 engine-core”。
5. `deployment/README.md` 更新为当前 JMeter Runner 基线和腾讯镜像传输 + Apache 官方 SHA-512 校验说明，不再保留空 Compose 占位描述。
6. 提供 Windows PowerShell 一键门禁脚本，顺序执行根 Maven 测试、前端测试/类型检查/构建、WSL Docker Compose 配置检查；任一步非零立即退出。若提供 Bash 入口，行为需一致。
7. 全部新增说明文字为中文；下载源遵守全局规则：国内镜像可加速，制品仍用官方哈希验证。

## TDD 与验收

1. 先建立会因当前缺失/陈旧配置而失败的合同检查或一键脚本存在性检查，记录红灯，再修改配置。
2. 新鲜执行并记录退出码：
   - `mvn clean test`
   - `npm.cmd --prefix web test -- --run`
   - `npm.cmd --prefix web run typecheck`
   - `npm.cmd --prefix web run build`
   - WSL 中 `docker compose -f deployment/docker-compose.yml config`
   - 新增的一键门禁脚本本身
3. 验证 package.json/lockfile 无版本漂移标记，`.gitignore` 覆盖必需目录和密钥文件，README 无陈旧 `engine-core` 未来时描述。
4. 结果写入中文 `F0-03-report.md`，区分本任务门禁通过与“整个平台可用”两种范围；不得越界宣称平台完成。
