# F1-02 实施报告：单用户 Session 登录

## 1. 结论与范围

- 状态：**完成，Sol 复审 PASS / APPROVE**。
- 本阶段完成单用户、同源、服务端 `HttpSession` 登录闭环；登录用户拥有当前平台全部功能，不引入 RBAC、JWT、Redis 或多 Runner。
- 本阶段验证了后端认证接口、前端登录与 Session 恢复、真实浏览器登录/退出/改密，以及本地门禁和 E2E 进程清理。
- F1-02 不实现资产模型、接口/场景编辑、Runner 调度、JMeter 执行或 F1-03 业务功能。现有工作台仍是登录后的既有原型壳和本地交互数据。

## 2. TDD 过程

### 2.1 后端红灯到绿灯

先执行认证集成测试，认证入口、安全过滤器和 Session/CSRF 行为尚未具备时保持红灯；随后以最小实现补齐认证 Controller、JDBC 用户仓储、管理员初始化、Security 配置、统一错误和限流，再连接真实 PostgreSQL 验证为绿灯。

后端真实 PostgreSQL 验收按四项场景记录：

1. 空库管理员初始化、用户名标准化、BCrypt 非明文保存，以及已有用户重启后不被新环境密码覆盖。
2. `/actuator/health` 匿名可用、未登录 `/api/v1/auth/me` 返回统一 401，登录成功返回用户和 Session/XSRF Cookie。
3. 登录失败统一文案、CSRF 缺失拒绝、带 `X-XSRF-TOKEN` 的退出成功，退出后 Session 失效。
4. 连续失败锁定、成功登录清理失败状态，以及改密后当前 Session 失效、旧密码失效和新密码可登录。

### 2.2 前端红灯到绿灯

先新增 HTTP、Session、路由和登录视图测试，再运行前端测试：新增的四组测试因生产文件尚未实现而红灯；原有原型状态测试 10 项保持通过。随后完成最小实现，最终 5 个测试文件共 19 项通过。

## 3. 后端实现能力

- `Spring Security` 保护 `/api/v1/**`，仅登录接口和健康检查匿名可访问；认证结果保存到服务端 HttpSession，并在登录成功时迁移 Session ID。
- 使用 `JdbcTemplate` 访问既有 `users` 表；管理员只从 `AUTOTEST_ADMIN_USERNAME`、`AUTOTEST_ADMIN_PASSWORD` 初始化，用户名执行 Unicode NFKC、去首尾空白和 `Locale.ROOT` 小写，密码使用 BCrypt。
- 提供 `POST /api/v1/auth/login`、`POST /api/v1/auth/logout`、`GET /api/v1/auth/me`、`PUT /api/v1/auth/password`。
- API 错误统一为包含 `code`、`message`、`details`、`traceId` 的 JSON；未知用户、密码错误和锁定状态均执行一次 BCrypt 校验并返回相同错误，避免通过响应内容或明显的密码校验分支判断账号状态。
- CSRF 保持启用：仅登录接口免 CSRF；登录成功写入可读 `XSRF-TOKEN` Cookie，写请求要求 `X-XSRF-TOKEN`；Session Cookie 为 HttpOnly、SameSite=Lax，Secure 由环境变量控制。
- 登录失败限制为单实例内存状态，按远端地址和标准化用户名维护最近 5 分钟的失败时间队列；第 5 次失败后临时锁定，成功登录清除状态。时间源通过 `Clock` 注入，滑动窗口、跨边界累计、锁定到期和成功清除均可确定性测试。

关键文件包括 `platform-api/src/main/java/com/autotest/platform/auth/`、`platform-api/src/main/java/com/autotest/platform/security/`、`platform-api/src/test/java/com/autotest/platform/auth/AuthPostgresqlIntegrationTest.java` 和 `platform-api/src/main/resources/application.yml`。

## 4. 前端实现能力

- `web/src/api/http.ts` 使用相对路径和 `credentials: same-origin`；从 `XSRF-TOKEN` Cookie 读取令牌并为写请求设置 `X-XSRF-TOKEN`，统一解析 `{code,message,details,traceId}` 错误。
- `web/src/auth/session.ts` 在启动时调用 `/api/v1/auth/me` 恢复 Session；登录、退出、改密会同步本地状态。
- `web/src/router.ts` 提供登录页和受保护的平台首页：未登录访问平台转到 `/login`，已登录访问登录页转回平台首页。
- `web/src/views/LoginView.vue` 提供用户名、密码、提交中状态和统一错误提示。
- 现有平台外壳移至 `web/src/views/PlatformShellView.vue`；头像菜单提供当前用户、退出登录和修改密码入口，改密成功后回到登录页。
- `web/src/main.ts` 完成 Session 恢复、依赖注入、Router 安装和应用挂载；`web/vite.config.ts` 仅增加本地 `/api` 到 Platform API 的代理。

## 5. 真实浏览器 E2E 与清理修复

`web/e2e/auth.spec.ts` 通过 `scripts/Test-F1-02E2E.ps1` 启动隔离 PostgreSQL 容器、真实 Platform API、真实 Vite 和 Chromium，不使用 Mock、不跳过 CSRF。流程覆盖：

- 未登录访问平台跳转登录页；错误密码展示统一文案且不泄露“用户不存在”。
- 正确登录进入 AI 工作台，刷新后仍保持登录。
- 退出后回到登录页，真实 `/api/v1/auth/me` 返回 401。
- 重新登录后修改密码，旧密码失败、新密码成功。

初次真实 E2E 虽然业务流程通过，但发现脚本仅停止 `npm.cmd` 父进程会遗留本次 Vite 进程树。修复后：

- Web/API 均按本次启动根 PID 使用 `taskkill /PID ... /T /F` 清理，不按目录或进程名宽杀。
- 脚本结束前按本次随机 WebPort 检查 `--strictPort` Node、监听端口和原始进程树；同时检查 API 监听和 Maven 子进程树，残留即使脚本失败。
- 修复后的最终复跑：E2E `1 passed`；WebPort `35119` 的 Vite 进程和监听均为 0，ApiPort `19272` 的 Maven 进程和监听均为 0，随机 PostgreSQL 容器 `autotest-f1-02-db-5271bd7067` 不存在。
- 数据库容器删除失败不再只输出告警：脚本会继续尽力清理 Web/API，再聚合原始测试错误与清理错误并以非零退出；结束前还会按随机容器名再次确认容器不存在。
- 脚本已确认 Windows PowerShell 5.1 可解析，并保持 UTF-8 BOM。

## 6. 依赖与配置

后端使用 Spring Boot `3.4.3`、Testcontainers `1.21.4`，新增 `spring-boot-starter-security`，保留 JDBC，不引入 JPA。前端精确锁定：

| 依赖 | 版本 |
| --- | --- |
| Vue | 3.5.42 |
| Vue Router | 4.5.1 |
| Vitest | 3.2.7 |
| Vue Test Utils | 2.4.6 |
| jsdom | 26.1.0 |
| Playwright Test | 1.63.0 |
| Vite | 6.4.3 |
| TypeScript | 5.9.3 |
| vue-tsc | 2.2.12 |

## 7. 新鲜验证证据

以下结果来自 2026-09-10 本轮整改后的主控定向验证、一次真实 E2E、一次总门禁和修复后的限流定向复核：

| 验证项 | 结果 |
| --- | --- |
| 后端认证定向测试 | 首轮 4 个测试类、6 项通过；滑动窗口整改后限流测试 3 项通过 |
| 前端单元/组件测试 | 5 个文件、19 项通过 |
| 前端 typecheck | 退出码 0 |
| 前端 production build | 退出码 0 |
| 真实浏览器 E2E | 1 项通过 |
| Windows PowerShell 一键本地门禁 `scripts/verify-local.ps1` | 退出码 0；Maven 共 32 项、前端 19 项、typecheck/build 与 Compose 配置检查通过 |
| E2E 清理后进程、端口和容器复核 | Web/API/容器均无本次运行残留 |

一键门禁表示当前本地合同、Maven、前端测试/类型检查/构建和 Compose 配置检查通过；最后一处滑动窗口修改另由定向测试和 Sol 独立复审验证。

## 8. 未验证项与边界

- Sol 聚焦复审结论为 `PASS / APPROVE`，Critical、Important、Minor 均为 0，允许进入 F1-03；完整证据见 `F1-02-review.md`。
- 本阶段未验证多实例 Session、Redis、代理转发下的分布式限流，也未实现这些能力。
- 本阶段未扩展 F1-03 资产、接口用例、场景执行或 Runner 功能；后续任务仍需单独设计、实现和验收。
- E2E 使用本地隔离 PostgreSQL 和开发服务器，不能替代生产部署、HTTPS Secure Cookie 或多实例部署验收。

## 9. 首轮复审整改

首轮 Sol 复审在额度中断前给出四项明确问题，本轮仅针对这些问题整改：

1. 匿名写请求缺少 CSRF 时，统一返回 401；已登录但 CSRF 错误仍返回 403。
2. 未知用户、错密和锁定分支均执行 BCrypt，且对外响应一致。
3. 限流器改为注入 `Clock`，并在复审发现固定窗口会漏算跨边界失败后，改为真正的最近 5 分钟滑动窗口；补齐阈值、跨边界累计、锁定到期和成功清除测试。
4. 补齐 BCrypt 非明文、NFKC 用户名、Session ID 迁移、Cookie 属性、Secure 开关、密码版本递增，以及 E2E 资源清理失败传播证据。

整改后 Sol 独立复核认证测试 6 项和滑动窗口测试 3 项，确认四类整改及跨窗口问题均已关闭，最终结论 `PASS / APPROVE`。
