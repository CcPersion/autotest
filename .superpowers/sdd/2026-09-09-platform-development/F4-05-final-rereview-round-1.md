# F4-05 最终整改 Sol 独立复审（Round 1）

- `review_model`: `gpt-5.6-sol`
- `review_effort`: `high`
- `review_scope`: 当前 `deployment/scripts/verify-f4-05-compose.sh`、`deployment/scripts/test-verify-f4-05-compose.sh`、`deployment/docker-compose.yml`，指定新鲜证据 `.superpowers/f4-05-evidence/autotest-f405-real3-20260913/`；只读追踪 `RunnerConfiguration -> Runner heartbeat -> RunnerStatusRepository/状态 API`，并对上一轮三项阻断执行静态检查和最小反例复现。除本审查报告外未修改业务代码、配置、测试或需求文档。
- `verdict`: **FAIL**

## 阻断问题

### 1. `sanitize_file` 仍不是完整的结构化 JSON 脱敏，动态 Authorization 可在保留证据中明文残留

- 位置：`deployment/scripts/verify-f4-05-compose.sh:192-307, 744-798`；`deployment/scripts/test-verify-f4-05-compose.sh:306-349`；`deployment/scripts/test-fixtures/npm:11-14`。
- 证据：实现按 `splitlines(keepends=True)` 逐行调用 `json.loads`。常见的多行格式化 JSON 不能逐行解析，只会落入单行正则；跨行的 `{ "name": "Authorization", "value": "CustomScheme ..." }` 因 `name` 与 `value` 不在同一行而完全漏过。用当前脚本中原样抽取的 Python 段（196-306 行）处理以下输入后，`pretty-header-secret` 仍原样存在：

  ```json
  {
    "headers": [
      {
        "name": "Authorization",
        "value": "CustomScheme pretty-header-secret"
      }
    ]
  }
  ```

- 第二个最小反例：合法单行 JSON `{"message":"request failed: Authorization: CustomScheme embedded-json-secret"}` 会被当成 JSON 成功解析，但递归函数不处理普通字符串值，因此 `embedded-json-secret` 也原样保留。相同的纯文本独立 Header 行会被遮蔽，说明缺口位于 JSON/混合日志分支，而非反例命令错误。
- 现有测试为何未发现：测试夹具只输出独立纯文本 Authorization 行和一行压缩 JSON；没有多行 JSON、跨行 header name/value 或 JSON 字符串内嵌 Header。cleanup 的 `secretLeakSentinelCount` 只扫描管理员密码和 `.env` 中已知静态敏感值，未知运行期 credential 泄漏不会自动计数。因此真实 evidence 的 `secretLeakSentinelCount=0` 只能证明本次样本未出现已知值，不能关闭此安全契约。
- 影响：Playwright、Compose、API 报告或失败日志一旦以格式化 JSON/header 数组或消息字符串记录动态 Bearer、Basic、自定义 scheme、CSRF/会话令牌，`--keep-evidence` 会保留可用凭据，并可能仍生成泄漏计数 0。这违反需求 6.2/10/11.3 的日志和证据无密钥明文边界。
- 明确整改要求：先尝试按**完整文件**解析 JSON 并递归脱敏对象/数组/`{name,value}` Header；对非 JSON 或混合日志，再对所有字符串载荷执行 Authorization 全值脱敏（任意 scheme），不能因 JSON 解析成功跳过字符串扫描。补充多行格式化 JSON、跨行 header pair、JSON 字符串内嵌 Authorization、Bearer/Basic/自定义 scheme 和未知动态值用例；要求最终 evidence 无明文，且无法可靠脱敏或检测到泄漏时 fail-closed。

### 2. Runner ID 负向测试是假阳性，删除“同一 runnerId”判断后测试仍通过

- 位置：`deployment/scripts/test-verify-f4-05-compose.sh:264-278`；总入口固定失败路径见 `deployment/scripts/verify-f4-05-compose.sh:926-938`。
- 证据：负例只断言 `STUB_STATUS != 0`，并在 stdout 中搜索 `restart_report_recovery...PASS`。但验收入口无论重启门禁是否通过，最后都会登记 8 项 `BLOCKED`、设置 `RUN_FAILED=1` 并 `exit 1`；`record_result` 也不打印到 stdout。故两个条件都不能区分“Runner ID 变化被拒绝”和“Runner ID 变化被接受但最终因 BLOCKED 返回 1”。
- 最小变异复现：在 `/tmp` 隔离副本中仅把主脚本 542 行从 `runnerId 不同或非 ONLINE 则跳过` 改为 `仅非 ONLINE 则跳过`，即故意删除同 ID 校验；随后执行未修改的 `test-verify-f4-05-compose.sh`，仍返回 `exit_code=0` 并打印门禁通过。这直接证明当前负向测试不能捕获其目标回归。
- 影响：当前生产脚本的同 ID 比较本身存在，且 real3 真实证据也满足同 ID；但仓库门禁不能防止后续把该关键比较删除或绕过，上一轮明确要求的行为级反例并未真正建立。
- 明确整改要求：负例必须读取该次 evidence 的 `results.json`/`results.tsv`，明确断言 `restart_report_recovery=FAIL`（并校验失败原因）；不能用入口总退出码代替该项结果。建议加入变异敏感性验证，确保删除 runnerId 比较时测试必定失败。Compose 透传测试也应检查解析后的服务环境或可观察 Runner ID，而不只记录 fake docker 进程继承到的宿主环境变量。

### 3. URL 安全行为已修复，但持久化回归测试未覆盖上一轮要求的完整负例矩阵

- 位置：`deployment/scripts/verify-f4-05-compose.sh:127-160`；`deployment/scripts/test-verify-f4-05-compose.sh:91-101`。
- 证据：当前实现确实只允许 `localhost`、`127.0.0.1`、`::1`，并拒绝 userinfo、query、fragment 和非法/缺失端口。本轮新鲜手工执行公网 Web、非回环 IP、公网 API、userinfo、query、fragment 六个输入，均在创建 evidence、调用 curl/Playwright/Compose 前以退出码 2 拒绝。因此原始凭据外发行为已关闭。
- 缺口：仓库定向测试只保留一个“公网 Web URL”负例，没有测试公网 API、非回环 IP、userinfo、query、fragment；上一轮明确要求的安全回归矩阵未落到自动门禁。
- 影响：当前行为正确，但 URL 校验被后续放宽时，现有测试可能继续通过。鉴于这是管理员凭据外发边界且属于上一轮明确整改条件，测试证据仍不充分。
- 明确整改要求：把上述六类输入做成表驱动负例，同时覆盖 Web/API 两个参数；每例断言退出码 2、明确错误，并用 fake curl/npm/docker 记录断言三者调用计数均为 0。

## 已关闭或证据可信的部分

- 专用 Runner ID：主脚本从 Compose project 的 SHA-256 派生 UUID 并 `export AUTOTEST_RUNNER_ID`，Compose 在 `runner-app.environment` 透传，`RunnerConfiguration.from` 读取并作为心跳路径 ID。real3 的实际 ID `c51ff3e5-0705-63f6-f523-a32cc919ad74` 与 project `autotest-f405-real3-20260913` 的派生值完全相同。
- 重启边界：baseline 为 `2026-09-12T18:03:10.451869Z`，runner 容器重启 `StartedAt=2026-09-12T18:03:12.135963705Z`，重启后同一 ID 的 `lastSeenAt=2026-09-12T18:03:22.266072Z`；后者同时严格晚于 baseline 和 StartedAt。两份报告重启后均为 `PASSED`。
- evidence 汇总：`results.tsv`/`results.json` 一致，10 项 required 全为 PASS（含两个浏览器闭环和重启恢复），8 项产品能力明确为 `BLOCKED`，`overall=FAIL`，没有误报 F4-05 或产品版本完成。
- Compose：构建日志显示 Platform API、Runner、Web 三镜像 Built；启动和重启快照均严格包含 postgres/platform-api/web/runner-app 四服务且 4/4 `running/healthy`。Runner 心跳报告 `jmeterVersion=5.6.3`，镜像构建日志包含 JMeter 5.6.3 发行包尺寸与上游 SHA-512 校验步骤，健康检查由 `jmeter --version` 参与。
- 清理与本次样本：`cleanup.status=PASS`；本轮 WSL 标签枚举确认该 project 的 container/network/volume 均为 0；evidence 中 `.cookie`/`login*.json` 为 0，当前 `.env` 四个敏感值在 evidence 中匹配数均为 0，宽泛敏感词形态扫描也未命中。本次样本没有观察到实际明文泄漏，但不能替代阻断 1 的反例。

## test_evidence

- `bash -n deployment/scripts/verify-f4-05-compose.sh`：退出码 0。
- `bash -n deployment/scripts/test-verify-f4-05-compose.sh`：退出码 0。
- 在不触碰工作区 `deployment/.env` 的 `/tmp/f405-review-repo` 隔离副本执行 `bash deployment/scripts/test-verify-f4-05-compose.sh`：退出码 0，耗时约 27 秒。
- `docker compose --project-name autotest-f405-review-config --env-file deployment/.env --file deployment/docker-compose.yml config --quiet`：退出码 0。
- URL 六类负向输入：公网 Web、非回环 IP、公网 API、userinfo、query、fragment 均退出码 2。
- Runner ID 测试变异复现：删除 `/tmp` 副本中的 ID 比较后，现有定向测试仍退出码 0，证明负例无判别力。
- 脱敏最小复现：多行 Header JSON 与 JSON 字符串内嵌 Authorization 均保留动态哨兵；独立纯文本 Authorization 对照组被遮蔽。
- real3 evidence 一致性检查：10 PASS、8 BLOCKED、0 其他状态；`overall=FAIL`、`cleanup=PASS`、`secretLeakSentinelCount=0`；23 条命令全部 `exit=0`；启动/重启健康快照均 4/4；两个 Playwright 日志均 `1 passed (8.4s)`；专用资源实时枚举 0/0/0。

## remaining_risks

- 8 项产品能力仍为明确 `BLOCKED`：十类参考夹具、AI Patch、Cron、取消、Runner 异常、MinIO 附件、通知/导出、PostgreSQL 备份恢复。即使本报告的三项整改完成，也只能复审本验收入口，不能宣称 F4-05 或需求版本完成。
- 当前目录不是 Git 工作树，无法按提交差异确认本轮仅修改指定文件；本结论绑定报告开头列出的当前文件内容和指定 real3 evidence。
- 当前真实样本没有动态 Authorization 泄漏，不代表脱敏器安全；阻断 1 是用当前生产函数直接复现的确定性缺口。

本轮仍有阻断，必须交回 Luna 整改并重新测试；整改后由 Sol 对最新文件和新鲜 evidence 再审。旧审查结论对后续修改自动失效。
