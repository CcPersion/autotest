# F1-02 独立复审报告：单用户 Session 登录

## 1. 复审结论

- 功能结论：**PASS**。
- 审批结论：**APPROVE**。
- 后续门禁：**允许进入 F1-03**。
- 阻断问题：Critical 0 项，Important 0 项。
- 非阻断问题：Minor 0 项。

本结论只批准 F1-02 的单用户登录边界，不代表 F1-03 项目/模块管理、后续 Runner/JMeter 执行链路、多实例部署或生产 HTTPS 已完成。

## 2. 审查依据与范围

本轮以以下文件为权威范围：

- `docs/requirements/01-产品需求规格说明书.md`
- `docs/tasks/01-开发任务清单.md` 的 F1-02
- `.superpowers/sdd/2026-09-09-platform-development/F1-02-brief.md`
- `.superpowers/sdd/2026-09-09-platform-development/F1-02-report.md`

复审覆盖 Platform API 的认证、安全配置、POM/配置与测试，Web 的 HTTP/Session/路由/登录/平台外壳/组件测试和真实浏览器 E2E，以及 `scripts/Test-F1-02E2E.ps1`。仓库不是 Git 仓库，本结论以当前文件、主控本轮新鲜门禁记录和复审者独立定向测试为准。

## 3. 分级问题

### Critical

无。

### Important

无。

首轮复审发现的限流窗口边界已经关闭：`LoginRateLimiter` 现在按“远端地址 + NFKC 标准化用户名”保存失败时间戳队列，每次操作剔除不属于最近 5 分钟的记录。跨旧固定窗口边界的 `t=0`、`t=299s`、`t=300s + 4 次` 场景会保留最近 5 分钟内的 5 次失败并立即锁定，不再漏计。

### Minor

无。

## 4. 关键安全与契约核对

1. 初始管理员只在 `users` 空表时创建；已有任意用户时部署变量不会新增、改名或覆盖密码。数据库保存 NFKC、去首尾空白、`Locale.ROOT` 小写后的用户名。
2. 密码使用 BCrypt，真实 PostgreSQL 测试同时检查非明文 hash 形态并以登录成功证明 hash 可用；未引入 JPA。
3. 未知用户使用固定 BCrypt dummy hash，错误密码使用已存 hash；锁定检查在认证计算之后执行，因此未知用户、错密和锁定请求均执行一次 BCrypt，并返回相同的 401 错误结构和文案。
4. 登录是 `/api/v1` 唯一 CSRF 豁免写接口；匿名 GET/POST/PUT 受保护接口返回统一 401，已认证 Session 缺失或错误 CSRF 返回统一 403。错误体包含 `code`、`message`、`details`、`traceId`。
5. 登录显式创建并迁移 Session ID；`JSESSIONID` 为 `HttpOnly`、`SameSite=Lax`，`Secure` 受 `AUTOTEST_SESSION_COOKIE_SECURE` 控制；`XSRF-TOKEN` 可由前端读取且登录时重新生成。
6. Logout 和修改密码均受 CSRF 保护；改密以事务更新 BCrypt hash、`revision + 1`，成功后当前 Session 失效，旧密码不可再登录。
7. 限流采用最近 5 分钟滑动窗口，第 5 次失败锁定 5 分钟；`Clock` 可注入，阈值、跨边界、锁定到期和成功清除均可确定性测试。键只使用 `request.remoteAddr`，不信任未配置代理的 `X-Forwarded-For`。
8. 前端启动调用 `/api/v1/auth/me` 恢复 Session；路由守卫处理匿名访问与已登录访问登录页；HTTP 封装使用相对路径、`credentials: same-origin` 和 `X-XSRF-TOKEN`；真实浏览器流程覆盖登录失败/成功、刷新恢复、退出和改密后重新登录。
9. E2E 使用隔离 PostgreSQL、真实 Platform API、Vite 和 Chromium。Web/API 进程树、监听端口及随机数据库容器均按本次资源精确清理；原始测试错误和清理错误会被聚合，任一清理失败最终均以非零退出，原始错误不会丢失。
10. 未发现 JWT、RBAC、角色矩阵、Redis Session/限流、JPA、多 Runner 或 F1-03 资产接口越界。现有工作台中的后续能力仍明确标注为交互原型和本地数据。

## 5. 新鲜验证证据

### 复审者独立执行

```powershell
wsl.exe -d Ubuntu -- bash -lc "cd /mnt/d/codexWorkSpec/autotest && mvn -pl platform-api -am -Dtest='*Auth*Test' -Dsurefire.failIfNoSpecifiedTests=false test"
```

- 结果：退出码 0，4 个认证测试类共 6 项通过，0 失败、0 错误、0 跳过。
- 其中真实 PostgreSQL 集成覆盖管理员初始化与不覆盖、NFKC、BCrypt、匿名 401、已认证 CSRF 403、Session/Cookie、锁定、改密和 revision 生命周期。

滑动窗口最终修复后，仅复跑对应测试类：

```powershell
wsl.exe -d Ubuntu -- bash -lc "cd /mnt/d/codexWorkSpec/autotest && mvn -pl platform-api -am -Dtest=AuthLoginRateLimiterTest -Dsurefire.failIfNoSpecifiedTests=false test"
```

- 结果：退出码 0，3 项通过，0 失败、0 错误、0 跳过；包含最近 5 分钟跨边界回归。

### 采用的主控本轮证据

- 真实浏览器 E2E：1 项通过，且本次 Web/API 进程、监听端口和随机 PostgreSQL 容器无残留。
- `scripts/verify-local.ps1`：退出码 0；Maven 32 项、前端 19 项、typecheck、production build 和 Compose 配置检查通过。

根据聚焦复审要求，本轮未重复执行真实 E2E 和总门禁；上述两项采用主控在同一整改轮次提供的新鲜结果。

## 6. 未验证项与已知边界

- 未做生产 HTTPS 环境中的浏览器 Secure Cookie 验收；当前由真实后端响应测试证明 Secure 开关的 Set-Cookie 行为。
- 未验证多实例 Session、分布式限流、代理信任链或 Redis；这些能力明确不属于 F1-02。
- 未执行 F1-03 及后续资产、Runner、JMeter 业务验收；本报告只允许开始 F1-03，不代表这些能力已完成。

## 7. 最终判定

F1-02 的权威目标、验收条件和实施简报约束均有对应实现与新鲜证据；首轮发现的匿名写请求状态码、BCrypt 时序分支、限流可测性/滑动窗口边界和 E2E 清理失败传播问题均已关闭。最终结论为 **PASS / APPROVE**，**允许进入 F1-03**。
