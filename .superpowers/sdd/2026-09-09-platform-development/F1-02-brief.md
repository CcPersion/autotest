# F1-02 实施简报：单用户 Session 登录

## 目标与边界

以 Spring Security 实现同源、服务端 `HttpSession` 登录，不使用 JWT。接口固定为 `POST /api/v1/auth/login`、`POST /api/v1/auth/logout`、`GET /api/v1/auth/me`、`PUT /api/v1/auth/password`；仅登录和 `/actuator/health` 匿名可访问，其余 `/api/v1/**` 默认返回 401。登录用户拥有全部功能，不增加 RBAC、角色、Redis、找回密码或多实例限流。

所有 API 错误（含 401、403、校验和业务异常）统一为 `{code,message,details,traceId}` JSON。密码只存 BCrypt；登录失败对“用户不存在、密码错误、锁定中”使用相同对外文案，不泄露账号存在性。

## 后端实施契约

- `platform-api/pom.xml`：增加 `spring-boot-starter-security`；保留 JDBC，不引入 JPA。
- `platform-api/src/main/java/com/autotest/platform/auth/`：新增认证 Controller、Service、基于 `JdbcTemplate` 的 `UserRepository`、DTO、用户名标准化器、管理员初始化器和登录失败限制器。用户名统一执行 Unicode NFKC、去首尾空白和 `Locale.ROOT` 小写；数据库始终保存标准化值。
- `platform-api/src/main/java/com/autotest/platform/security/`：新增 `SecurityFilterChain`、JSON 认证/拒绝处理器、统一异常响应和 `traceId` 过滤器。Session Cookie 为 `HttpOnly`、`SameSite=Lax`，生产由 `AUTOTEST_SESSION_COOKIE_SECURE=true` 开启 `Secure`；认证成功迁移 Session ID。
- `platform-api/src/main/resources/application.yml`：读取 `AUTOTEST_ADMIN_USERNAME`、`AUTOTEST_ADMIN_PASSWORD` 和 Cookie 安全开关，不写默认密码。Flyway 完成后：`users` 为空时两项变量必须齐全并创建首个管理员；只要已有任意用户就绝不新增、改名或覆盖密码。
- 不修改已执行的 `V1__create_core_schema.sql`，现有 `users(id,username,password_hash,revision,created_at,updated_at)` 足够；修改密码使用事务、更新 BCrypt hash、`revision+1`，成功后注销当前 Session，要求重新登录。
- 限流为单实例内存状态：键为 `request.remoteAddr + 标准化用户名`，5 分钟内第 5 次失败即锁 5 分钟；锁定期间即使密码正确也拒绝，成功登录清除此键，惰性清理过期项，进程重启后清空。不信任未配置代理产生的 `X-Forwarded-For`。

CSRF 明确启用：登录接口是唯一免 CSRF 的 `/api/v1` 写接口；登录成功显式生成并保存 `XSRF-TOKEN` Cookie（该令牌 Cookie 必须可被 JS 读取，Session Cookie 仍为 HttpOnly）。前端后续 `logout/password` 及所有写请求从 Cookie 读取令牌并发送 `X-XSRF-TOKEN`；缺失或错误返回统一 JSON 403。Vite 开发服务器用 `/api` 反向代理保持浏览器同源，不开放宽泛 CORS。

## 前端与文件范围

- `web/package.json`、`web/package-lock.json`：精确锁定 `vue-router`、组件测试依赖和 Playwright 测试依赖。
- `web/src/api/http.ts`、`web/src/api/session.ts`：相对路径请求、`credentials: same-origin`、CSRF 请求头、统一错误解析。
- `web/src/auth/session.ts`、`web/src/router.ts`：启动时调用 `/me` 恢复 Session；受保护路由未登录转 `/login`，已登录访问登录页转 `/`。
- `web/src/views/LoginView.vue`：用户名/密码、提交中状态和统一错误；成功进入现有 AI 工作台。
- `web/src/App.vue`、`web/src/main.ts`：接入 `RouterView`；将现有平台外壳移入 `web/src/views/PlatformShellView.vue`，头像菜单提供退出和修改密码入口。
- `web/vite.config.ts`：仅为本地开发增加 `/api` 到 Platform API 的代理。
- 测试放入 `platform-api/src/test/java/com/autotest/platform/auth/`、`web/src/**/*.test.ts`、`web/e2e/auth.spec.ts`；增加 `scripts/Test-F1-02E2E.ps1` 负责启动隔离 PostgreSQL、真实 Platform API 与 Web 后执行浏览器流程。完成后只更新本阶段 `F1-02-report.md` 和 `progress.md`。

不修改 `runner-app`、`shared-contracts`、JMeter、资产表和权威需求/任务文档；完整持久化审计表/页面留给 F4-03，本任务只输出带 `traceId` 且不含密码的登录安全日志。

## TDD 与验收

1. 先写真实 PostgreSQL Testcontainers 红灯：空库创建管理员、重启且更换环境密码不覆盖已有用户、BCrypt 非明文、四个接口、默认 401、health 例外、统一错误、CSRF、注销/改密失效、5 次/5 分钟与锁 5 分钟、重启清锁。
2. 再写前端组件红灯：登录成功/失败、`/me` 恢复、路由守卫、CSRF 头、退出和改密后回登录页；然后最小实现。
3. 最后浏览器 E2E 连接真实 PostgreSQL：未登录跳登录页，错误密码不泄露用户状态，正确登录进入工作台，刷新仍登录，退出后业务 API 为 401；再验证改密后旧密码失败、新密码成功。

```powershell
wsl.exe -d Ubuntu -- bash -lc "cd /mnt/d/codexWorkSpec/autotest && mvn -pl platform-api -am -Dtest='*Auth*Test' -Dsurefire.failIfNoSpecifiedTests=false test"
npm.cmd --prefix web test -- --run
npm.cmd --prefix web run typecheck
npm.cmd --prefix web run build
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Test-F1-02E2E.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-local.ps1
```

只有真实 PostgreSQL 后端集成、前端组件测试、真实浏览器 E2E 和现有全量门禁均有本轮新鲜通过证据，才能声明 F1-02 完成；任一环境门禁无法执行时必须明确记为未验证。
