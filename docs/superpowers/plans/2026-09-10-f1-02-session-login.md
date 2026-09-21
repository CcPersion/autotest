# F1-02 单用户 Session 登录实施计划

> **面向 Agent：** 必须逐项执行本计划；每个行为先写测试并确认红灯，再写最小实现。

**目标：** 基于 Spring Security 与服务端 HttpSession 建立单登录用户的认证闭环，并通过真实 PostgreSQL、前端组件测试和真实浏览器 E2E 验证。

**架构：** 后端使用 JdbcTemplate 访问既有 `users` 表，Spring Security 保存认证 Session；CSRF 使用可读 `XSRF-TOKEN` Cookie 与 `X-XSRF-TOKEN` 请求头，业务 API 默认拒绝未登录请求。前端增加相对路径 HTTP 封装、Session 状态、路由守卫和登录/退出/改密界面，保留现有工作台为登录后的平台壳。

**技术栈：** Spring Boot 3.4.3、Spring Security、JdbcTemplate、BCrypt、PostgreSQL Testcontainers 1.21.4、Vue 3、Vue Router、Vitest、Playwright。

**依据：** `.superpowers/sdd/2026-09-09-platform-development/F1-02-brief.md`、`docs/tasks/01-开发任务清单.md`、`AGENTS.md`。

## 全局约束

- 只有一个登录用户类型；不增加 JWT、RBAC、Redis、JPA、找回密码、多实例限流或 F1-03 资产功能。
- 不修改已执行的 `V1__create_core_schema.sql`，不修改 `runner-app`、`shared-contracts` 和 JMeter 资产。
- 管理员只由 `AUTOTEST_ADMIN_USERNAME`、`AUTOTEST_ADMIN_PASSWORD` 初始化；无默认密码，已有用户时不覆盖、不改名、不新增。
- 所有 API 错误统一为 `{code,message,details,traceId}`；不泄露用户存在性、密码或数据库连接信息。
- PowerShell 脚本必须 UTF-8 BOM，并用 Windows PowerShell 5.1 实跑。

### 任务一：后端真实 PostgreSQL 红灯

**文件：**

- 新增：`platform-api/src/test/java/com/autotest/platform/auth/AuthPostgresqlIntegrationTest.java`
- 修改：测试启动辅助配置（仅为测试提供管理员环境变量）

- [ ] 写测试覆盖：空库管理员初始化、BCrypt 非明文、`/me` 默认 401、health 匿名、登录成功/失败统一文案、CSRF、logout、password 修改后 Session 失效、失败 5 次锁定、成功清锁、已有用户不被新环境密码覆盖。
- [ ] 使用现有 PostgreSQL Testcontainers 真实启动应用，运行 `mvn -pl platform-api -am -Dtest='*Auth*Test' -Dsurefire.failIfNoSpecifiedTests=false test`，确认因认证入口/配置不存在而红灯。

### 任务二：前端红灯

**文件：**

- 新增：`web/src/api/http.test.ts`、`web/src/auth/session.test.ts`、`web/src/router.test.ts`、`web/src/views/LoginView.test.ts`
- 新增：`web/src/api/http.ts`、`web/src/api/session.ts`、`web/src/auth/session.ts`、`web/src/router.ts`、`web/src/views/LoginView.vue`（测试先写，随后实现）

- [ ] 写测试覆盖相对路径与同源凭据、CSRF 请求头、统一错误解析、`/me` 恢复、未登录路由跳转、登录页已登录重定向、登录成功/失败展示、退出与改密回登录页。
- [ ] 运行 `npm.cmd --prefix web test -- --run`，确认测试先因文件/行为缺失红灯。

### 任务三：后端最小实现

**文件：**

- 修改：`platform-api/pom.xml`、`platform-api/src/main/resources/application.yml`
- 新增：`platform-api/src/main/java/com/autotest/platform/auth/` 下 Controller、Service、Repository、DTO、用户名标准化器、管理员初始化器、失败限制器
- 新增：`platform-api/src/main/java/com/autotest/platform/security/` 下 SecurityFilterChain、JSON 认证/拒绝处理器、统一异常响应、traceId 过滤器

- [ ] 实现 Unicode NFKC、首尾空白去除和 `Locale.ROOT` 小写标准化。
- [ ] 实现管理员初始化、BCrypt 校验、四个认证接口和事务改密；改密成功销毁当前 Session。
- [ ] 实现 5 分钟内第 5 次失败锁 5 分钟的单实例内存限制器，键为远端地址与标准化用户名；不信任未配置代理的 `X-Forwarded-For`。
- [ ] 启用 CSRF，仅登录接口免 CSRF；认证成功生成 `XSRF-TOKEN`，Session Cookie HttpOnly、SameSite=Lax，Secure 由配置打开。
- [ ] 逐个运行后端红灯测试转绿，再运行 F1-01 集成测试确认测试管理员变量不会破坏空库启动。

### 任务四：前端最小实现

**文件：**

- 修改：`web/package.json`、`web/package-lock.json`、`web/src/main.ts`、`web/src/App.vue`、`web/vite.config.ts`
- 新增：`web/src/views/PlatformShellView.vue`

- [ ] 安装并精确锁定 Vue Router、组件测试和 Playwright 依赖；Vite 只增加 `/api` 到 Platform API 的本地代理。
- [ ] 接入 RouterView、Session 初始化和受保护路由；未登录跳 `/login`，已登录访问 `/login` 跳 `/`。
- [ ] 登录页实现用户名、密码、提交中状态和统一错误；平台壳保留既有工作台，头像菜单提供退出与修改密码入口。
- [ ] 逐个运行前端红灯测试转绿，再运行 typecheck 与 build。

### 任务五：真实浏览器 E2E 与门禁

**文件：**

- 新增：`web/e2e/auth.spec.ts`、`scripts/Test-F1-02E2E.ps1`

- [ ] 脚本启动隔离 PostgreSQL、真实 Platform API 和 Web，设置测试管理员变量，使用随机临时端口与安全清理。
- [ ] Playwright 验证未登录跳登录页、错误密码不泄露用户状态、正确登录进入工作台、刷新仍登录、退出后业务 API 401、改密后旧密码失败且新密码成功。
- [ ] 用 Windows PowerShell 5.1 运行 E2E，再运行 brief 中的 Maven、前端测试/typecheck/build 和 `scripts/verify-local.ps1`。

### 任务六：证据文档

**文件：**

- 新增：`.superpowers/sdd/2026-09-09-platform-development/F1-02-report.md`
- 修改：`.superpowers/sdd/2026-09-09-platform-development/progress.md`

- [ ] 记录每轮红灯、绿灯、真实 PostgreSQL、前端和浏览器证据；所有中文说明保持 F1-02 范围。
- [ ] 状态写为“实现完成，待Sol复审”，不把局部测试通过写成 Sol 复审结论。
