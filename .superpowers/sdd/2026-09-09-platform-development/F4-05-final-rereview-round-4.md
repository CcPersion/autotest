# F4-05 最终整改 Sol 独立复审（Round 4）

- `review_model`: `gpt-5.6-sol`
- `review_effort`: `high`
- `review_scope`: 仅复审当前 `deployment/scripts/verify-f4-05-compose.sh`、`deployment/scripts/test-verify-f4-05-compose.sh`、`deployment/scripts/test-fixtures/npm`、`deployment/docker-compose.yml` 与既有 `.superpowers/f4-05-evidence/autotest-f405-real5-20260913/`；核对上一轮 mode=1 结构破坏阻断、URL 六负例、Runner ID 变化负例、NDJSON 解析及 real5 一致性。除本报告外未修改业务代码、配置、测试或文档。
- `verdict`: **PASS**

## 复审结论

上一轮唯一阻断已关闭。`sanitize_file` 现在把完整 JSON 片段与普通文本分段处理，普通文本正则不会再次处理结构化脱敏后的 JSON；Authorization 纯文本规则也放在通用字段规则之后，未再出现额外 `]` 或 JSON 截断。

mode=1 当前会真实经过两次 fake npm 浏览器调用。独立保留证据复现得到两份 `browser_*.log`，每份包含一条混合文本 Authorization 和三条 JSON/NDJSON 对象；共 6 条对象均可逐条解析，Authorization、headers 等结构保留，出现 28 处 `[REDACTED]`，14 类动态哨兵均为 0。仓库测试同步逐条 `json.loads`，并断言至少两条对象、Authorization 已脱敏、headers 结构存在、`[REDACTED]` 存在和全部哨兵消失，因此能捕获此前的结构破坏回归。

mode=2 独立复现得到两份可解析的 pretty JSON；跨行 Header 和 JSON 字符串内嵌对象均完成结构化脱敏，出现 14 处 `[REDACTED]`，动态 pretty/nested 哨兵为 0。

URL 六类负例仍为表驱动门禁，每例要求退出码 2、明确错误，并断言 fake docker/curl/npm 在校验前均未调用。Runner ID 变化负例读取该次 `results.json`，要求前置 9 项均 PASS 且 `restart_report_recovery=FAIL`，不再用总退出码替代专项结果。Compose fake `ps` 输出的 NDJSON 必须经健康解析后才能让前置门禁 PASS，mode=1 还对脱敏后的 JSON/NDJSON 逐条解析，断言具有实效。

## test_evidence

- 当前文件 SHA-256：`verify-f4-05-compose.sh=BE54E6D9C479B18936990AA1D62AE0267477ACD1C75AAFC2DA573F42A7619EB1`；`test-verify-f4-05-compose.sh=9896460D2E2EE13156C3DF2C542D745C302FB87EF4A07BBF9B3A979B5A306372`；`test-fixtures/npm=2554C4CD9A7AC71CE3E12789994D0FF78A01FBAEBD9AFCD9916C1B934B991A38`；`docker-compose.yml=3F0EA8C0B0E92F6D4C889152DEEE87F03D149EB9CA83C987AD7511B936099AB4`。
- `bash -n deployment/scripts/verify-f4-05-compose.sh`：退出码 0。
- `bash -n deployment/scripts/test-verify-f4-05-compose.sh`：退出码 0。
- 在不触碰工作区 `deployment/.env` 的 `/tmp/f405-review-round4-sol-20260913b` 隔离副本执行完整 `test-verify-f4-05-compose.sh`：退出码 0。
- `docker compose --project-name autotest-f405-round4-config --env-file deployment/.env --file deployment/docker-compose.yml config --quiet`：退出码 0。
- 独立 mode=1：npm 调用 2 次、浏览器日志 2 份、有效 JSON/NDJSON 对象 6 条、`[REDACTED]` 28 处、动态哨兵 0。
- 独立 mode=2：npm 调用 2 次、浏览器日志 2 份、两份 pretty/nested JSON 均可解析、`[REDACTED]` 14 处、动态哨兵 0。
- real5：required 10 PASS、blocked 8 BLOCKED、`overall=FAIL`、`cleanup=PASS`、`secretLeakSentinelCount=0`；23 条命令均 `exit=0`，cookie/login 临时文件 0。
- real5 Runner：启动后、重启前基线、重启后为同一派生 Runner ID；`after-start lastSeenAt < before-restart lastSeenAt < after-restart lastSeenAt`，且 runner 容器 `StartedAt < after-restart lastSeenAt`。

## remaining_risks

- real5 仍明确记录 8 项产品能力为 `BLOCKED`，且 `overall=FAIL`。本报告的 PASS 仅表示本轮指定验收入口、回归门禁和 real5 证据一致性不存在阻断；不得据此宣称完整 F4-05 或需求版本已经完成。
- 当前目录不是 Git 工作树，无法按提交差异证明 Luna 只修改了指定文件；本结论绑定上述 SHA-256 的当前文件内容。

