# F1-10 阶段一端到端验收简报

## 目标

在不扩展 F2 场景能力的前提下，证明登录、项目/环境、接口定义/用例、运行队列、JMeter 执行和原生报告能够在一套隔离环境中连成闭环。

## 本轮必须交付

- Runner 入口可从环境变量读取数据库、Platform API、回传 Token、JMeter 版本和工作目录配置。
- Runner 启动时恢复遗留 `RUNNING` 任务，并持续轮询单个 `PENDING` 任务。
- WSL Docker Compose 提供 Platform API、Web、PostgreSQL、Runner 和必要的持久化卷；启动后有健康检查。
- Playwright 真实浏览器流程覆盖登录、创建项目/环境、创建 JSON 接口定义与用例、触发运行、查看报告，并验证服务重启后报告仍可查询。
- 保存一键验收命令、退出码、关键日志和最小截图；失败时清理随机容器、端口和进程。

## 明确不做

- 不在本轮加入场景树、SQL/Redis、数据行、SSE、附件、导出、Allure、AI 生成或 F2 的完整请求语义。
- 不引入多 Runner、租约、fencing、消息队列、Kubernetes 或 RBAC。
- 不把当前页面原型数据当作真实报告证据；报告必须来自平台 API 保存的运行结果。

## 验收门禁

1. `mvn -pl runner-app -am clean test` 在 WSL Docker 中通过，并包含配置、队列、JMeter、JTL 和 Worker 测试。
2. `docker compose -f deployment/docker-compose.yml config` 通过，且每个服务的启动参数、密钥注入和卷路径可审计。
3. Playwright 一键流程在全新 PostgreSQL 卷上通过；重启 Platform API/Web/Runner 后使用同一运行编号再次查询报告。
4. 失败、取消、JMeter 非零退出和 Runner 重启均产生明确终态；任何密码、Token 或密钥哨兵不出现在浏览器响应、日志和报告中。
