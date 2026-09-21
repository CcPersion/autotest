# F4-05 Sol 独立最终复审

- `review_model`: `gpt-5.6-sol`
- `review_effort`: `high`
- `review_scope`: `deployment/scripts/verify-f4-05-compose.sh`、`test-verify-f4-05-compose.sh`、`test-fixtures/`、`test-f4-05-network-cli.sh`、`docs/reference-acceptance/F4-05-实施与验收记录.md`，以及 `f1-10-platform-flow.spec.ts`、`f2-04-data-rows.spec.ts`、`f2-05-scenario.spec.ts`、`f2-10-suite.spec.ts`、`f2-10-complex-suite.spec.ts`；同时只读追踪了管理员登录、Runner ID 生成和目标白名单真实调用链。
- `verdict`: **FAIL**

## 阻断问题

### 1. Runner 重启门禁可被重启前旧心跳误判为 PASS

- 位置：`deployment/scripts/verify-f4-05-compose.sh:721-723, 733-761`；`runner-app/src/main/java/com/autotest/runner/RunnerConfiguration.java:28-31`；`deployment/docker-compose.yml:68-76`。
- 证据：脚本在两段浏览器流程之前保存 `runner-status-after-start.json`，浏览器流程结束后才执行 Compose restart。真实证据中基线 Runner 为 `a8001b81-d0eb-4c2d-8d68-b48faefd22d9`，`lastSeenAt=2026-09-12T17:07:57.959030Z`；重启后状态同时出现新 Runner `8a6548d8-bf9d-4ffa-97cf-546de8b365c3`，旧 Runner 的记录为 `lastSeenAt=2026-09-12T17:08:19.037887Z`。脚本只要求旧 ID 的时间晚于早期基线，因此旧进程在浏览器测试期间、重启发生前产生的正常心跳已足以满足条件，最终错误记录 `restart_report_recovery=PASS`。当前 Runner 未配置 `AUTOTEST_RUNNER_ID` 时每次进程启动生成随机 UUID，Compose 也未固定该值，真实重启后的活动 Runner 确实已经换 ID。
- 影响：当前“同一 runnerId 且重启后 lastSeenAt 严格晚于基线”的关键验收结论不可信；旧心跳可假通过，真实运行结果中的该项 PASS 应撤销。
- 明确整改要求：在执行 restart 紧邻前采集并冻结目标 Runner 基线，并建立不可混淆的重启边界；重启后必须证明该目标 Runner 的心跳时间晚于重启边界。若产品验收契约要求同一 `runnerId`，还必须为该专用 Runner 提供跨进程重启稳定的 ID，并校验重启后活动记录的 ID 等于基线 ID。补充行为级反例：旧 ID 仅在 restart 前推进、restart 后出现新 ID 时必须 FAIL；完成 Luna 整改和新鲜真实 Compose 重跑后再审。

### 2. 显式 Web/API URL 可把管理员凭据发送到任意远程 HTTP(S) 主机

- 位置：`deployment/scripts/verify-f4-05-compose.sh:115-147, 448-452, 472-484`。
- 证据：`validate_url` 只检查 scheme、host、端口、凭据/query/fragment，不限制 loopback 或本次专用 Compose 端口。最小复现按同一判定逻辑验证 `https://attacker.example:443` 得到 `accepted=True`。之后 Playwright 会向 `WEB_URL` 页面填写 `E2E_ADMIN_PASSWORD`，`api_login` 会把用户名和密码 POST 到 `API_URL`。
- 影响：参数误配或不可信调用可直接造成验收管理员凭据外发；这违反本地专用 Compose 和无密钥泄漏边界。
- 明确整改要求：将 Web/API 目标限制为明确允许的本机回环地址和本次分配/显式绑定端口，或采用等价的强绑定校验；在任何凭据传递前完成校验。新增负向测试，证明公网域名、非回环地址、userinfo、query/fragment 全部以退出码 2 fail-fast，且 `curl`/Playwright 未被调用。

### 3. 保留证据路径的通用 Authorization 脱敏不完整

- 位置：`deployment/scripts/verify-f4-05-compose.sh:180-225, 234-254, 549-575, 603-645`；`deployment/scripts/test-verify-f4-05-compose.sh:49-57, 251-277`。
- 证据：当前正则对 `Authorization: Bearer runtime-secret-value` 的结果是 `Authorization: [REDACTED] runtime-secret-value`，仍留下令牌；对 JSON 常见形式 `"authorization":"Bearer runtime-secret-value"` 完全不替换。最终精确值扫描只覆盖 `E2E_ADMIN_PASSWORD` 和 `.env` 中按敏感键识别的静态值，无法识别运行期动态 Bearer、CSRF 或其他会话令牌。当前测试仅注入已知 `.env` 密码哨兵，未覆盖上述动态形式。
- 影响：一旦命令、Playwright 失败日志或报告响应包含运行期 Authorization，证据目录会保留可用凭据；`--keep-evidence` 场景尤其直接。当前真实证据未观察到该字符串，只能证明该次样本未触发，不能证明脱敏门禁完备。
- 明确整改要求：对结构化 JSON 按字段脱敏，并对文本中的 Authorization scheme 连同整段 credential 脱敏；覆盖大小写、引号、Bearer/Basic、自定义 scheme、header 对象 `{name,value}` 等形式。新增未知动态值哨兵测试，要求最终目录无明文且总结果 fail-closed；Luna 整改后重新运行定向测试和真实证据保留流程。

## 其余核对结果

- 五个 Playwright 夹具只在测试项目中显式填写 `platform-api` 或 `scenario-target` 白名单；生产链仍由 `ProjectService -> TargetPlanPolicy -> ExecutionPlanAdapter/JmeterPlanCompiler -> GuardedHttpSampler` 执行项目级白名单和运行时校验，未发现这些夹具放宽生产策略。
- 参数缺值、未知参数、非法 project、仓库路径边界、部分 `up` 失败后的 `down --volumes --remove-orphans`、Docker 枚举/inspect 失败的 fail-closed、管理员用户名统一来源与重登录等当前代码路径存在对应门禁。
- 真实结果正确保留 8 项 `BLOCKED`，`overall=FAIL`、总退出码 1，没有把未实现能力写成平台完成；专用 project 的容器、网络、卷当前枚举均为空。
- `docs/reference-acceptance/F4-05-实施与验收记录.md` 明确不宣称 F4-05 最终通过，但其中“全新 Compose 启动和浏览器闭环 NOT RUN”只是静态阶段记录，与后来生成的真实运行证据不是同一时点；不据此判定产品完成。

## test_evidence

- 新鲜只读检查：`npm.cmd run typecheck`，退出码 0。
- 新鲜语法检查：`bash -n deployment/scripts/verify-f4-05-compose.sh` 与 `bash -n deployment/scripts/test-verify-f4-05-compose.sh` 已通过；同一串命令随后运行定向桩测试时因当前存在用户本机 `deployment/.env` 按测试约束退出 1，未移动或覆盖该文件。
- 本轮读取真实证据 `.superpowers/f4-05-evidence/autotest-f405-real-20260913/`：10 项 required 为 PASS、8 项 BLOCKED、`overall=FAIL`、`cleanup.status=PASS`、`secretLeakSentinelCount=0`；`session.cookie` 不存在、`logs/login*.json` 数量为 0。
- 本轮 WSL 只读资源枚举：project `autotest-f405-real-20260913` 的 container/network/volume 均无输出，支持专用资源已清理。
- 已提供的新鲜执行证据：F1-10 Playwright `1 passed (6.5s)`、F2-10 suite `1 passed (8.2s)`、定向 stub/Bash/config PASS；本复审不重复启动 Compose。
- 最小安全复现：当前脱敏正则残留 Bearer credential，且当前 URL 判定接受公网 HTTPS 主机；见阻断 2、3。

## remaining_risks

- 8 项产品能力仍为明确 `BLOCKED`：十类参考夹具、AI Patch、Cron、取消、Runner 异常、MinIO 附件、通知/导出、PostgreSQL 备份恢复。即使以上三个审查阻断修复，仍不得宣称 F4-05 或产品需求版本完成。
- 当前目录不是 Git 工作树，本复审无法用提交差异确认“本轮仅修改指定文件”；结论基于当前文件内容、时间戳、权威需求和真实证据。
- `--keep-evidence` 开关在当前脚本除解析外未参与普通证据目录的保留/删除决策，CLI 语义与实际行为不清晰；整改脱敏问题时应一并明确并加入契约测试。

本轮存在阻断问题，必须交回 Luna 整改、重新测试，并由 Sol 对最新内容再次复审；本次 FAIL 不构成任何完成或放行结论。
