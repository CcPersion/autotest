#!/usr/bin/env bash
set -Eeuo pipefail

# 该门禁测试先验证危险入口在缺少本机配置时确实失败；不会创建 deployment/.env，
# 不读取、打印或猜测任何密码和令牌。
ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
HARNESS="${ROOT_DIR}/deployment/scripts/verify-f4-05-compose.sh"

if [[ -f "${ROOT_DIR}/deployment/.env" ]]; then
  echo "本测试要求工作区暂不创建 deployment/.env；请移走本机配置后再运行。" >&2
  exit 2
fi

set +e
output="$(bash "${HARNESS}" --compose-project autotest-f405-test 2>&1)"
status=$?
set -e

if [[ "${status}" -eq 0 ]]; then
  echo "缺少 deployment/.env 时验收入口意外成功。" >&2
  exit 1
fi
if [[ "${output}" != *"缺少 deployment/.env"* ]]; then
  echo "缺少 deployment/.env 时未得到明确 fail-fast 结果。" >&2
  exit 1
fi
if [[ "${output}" == *"请替换"* || "${output}" == *"密码"* ]]; then
  echo "fail-fast 输出不应暴露配置值或敏感提示细节。" >&2
  exit 1
fi

if ! grep -Eq 'python3 -c .*[Uu]rlsplit' "${HARNESS}"; then
  echo "URL 端口解析必须使用正确的 python3 -c 参数顺序。" >&2
  exit 1
fi
if ! grep -q 'COMPOSE_UP_ATTEMPTED' "${HARNESS}"; then
  echo "Compose up 开始后必须登记清理标记。" >&2
  exit 1
fi
if ! grep -q 'if ! ids="$(resource_ids' "${HARNESS}"; then
  echo "Docker 资源查询失败时不得 fail-open。" >&2
  exit 1
fi
if ! grep -q 'docker network ls -q' "${HARNESS}"; then
  echo "network 资源枚举必须使用 Docker 29 兼容的 ls -q。" >&2
  exit 1
fi

cleanup_body="$(sed -n '/^cleanup() {/,/^  exit "\${exit_code}"/p' "${HARNESS}")"
if [[ "${cleanup_body}" == *'if [[ "${KEEP_EVIDENCE}" != true ]]'* ]]; then
  echo "临时 cookie/login 文件必须无条件删除。" >&2
  exit 1
fi
if ! grep -Eq 'MASTER_KEY|PASSWORD|TOKEN|SECRET' "${HARNESS}"; then
  echo "泄漏扫描必须覆盖 .env 中的密码、令牌和主密钥。" >&2
  exit 1
fi
if ! grep -q 'ADMIN_USERNAME=' "${HARNESS}"; then
  echo "E2E 与 API 登录必须使用统一的管理员用户名来源。" >&2
  exit 1
fi

set +e
missing_arg_output="$(bash "${HARNESS}" --web-url 2>&1)"
missing_arg_status=$?
set -e
if [[ "${missing_arg_status}" -ne 2 || "${missing_arg_output}" != *"缺少值"* ]]; then
  echo "缺少选项值必须统一返回退出码 2。" >&2
  exit 1
fi

# 使用假的 docker 命令验证显式/默认 URL 解析和资源查询失败闭环；该桩不会启动 Compose。
ENV_FILE="${ROOT_DIR}/deployment/.env"
FIXTURES="${ROOT_DIR}/deployment/scripts/test-fixtures"
mkdir -p "${ROOT_DIR}/deployment/tmp"
TMP_ROOT="$(mktemp -d "${ROOT_DIR}/deployment/tmp/f4-05-test.XXXXXX")"
FAKE_BIN="${TMP_ROOT}/bin"
mkdir -p "${FAKE_BIN}"
cp "${FIXTURES}/f4-05.env" "${ENV_FILE}"
cp "${FIXTURES}/docker" "${FAKE_BIN}/docker"
cp "${FIXTURES}/bash" "${FAKE_BIN}/bash"
cp "${FIXTURES}/curl" "${FAKE_BIN}/curl"
cp "${FIXTURES}/npm" "${FAKE_BIN}/npm"
chmod +x "${FAKE_BIN}/docker" "${FAKE_BIN}/bash" "${FAKE_BIN}/curl" "${FAKE_BIN}/npm"
cleanup_stub() {
  rm -f -- "${ENV_FILE}"
  rm -rf -- "${TMP_ROOT}"
}
trap cleanup_stub EXIT

assert_url_rejected() {
  local case_name="$1"; shift
  local case_root="${TMP_ROOT}/url-matrix/${case_name}"
  local docker_log="${case_root}/docker.log"
  local curl_log="${case_root}/curl.log"
  local npm_log="${case_root}/npm.log"
  mkdir -p "${case_root}"
  set +e
  local output
  output="$(PATH="${FAKE_BIN}:${PATH}" \
    FAKE_DOCKER_LOG="${docker_log}" FAKE_CURL_RECORD="${curl_log}" FAKE_NPM_RECORD="${npm_log}" \
    E2E_ADMIN_PASSWORD=f405-test-e2e-password timeout 20s bash "${HARNESS}" \
    --compose-project "autotest-f405-url-${case_name}" --evidence-root "${case_root}/evidence" "$@" 2>&1)"
  local status=$?
  set -e
  if [[ "${status}" -ne 2 || "${output}" != *"WebUrl 或 ApiUrl"* ]]; then
    echo "URL 反例 ${case_name} 未在 Compose 启动前以退出码 2 fail-fast。" >&2
    exit 1
  fi
  for command_log in "${docker_log}" "${curl_log}" "${npm_log}"; do
    if [[ -s "${command_log}" ]]; then
      echo "URL 反例 ${case_name} 在门禁前调用了 fake 命令：${command_log}" >&2
      exit 1
    fi
  done
}

assert_url_rejected public-web \
  --web-url https://example.com:443 --api-url http://127.0.0.1:18081
assert_url_rejected public-api \
  --web-url http://127.0.0.1:41731 --api-url https://example.com:443
assert_url_rejected non-loopback-ip \
  --web-url http://192.0.2.10:41731 --api-url http://127.0.0.1:18081
assert_url_rejected userinfo \
  --web-url http://user:password@127.0.0.1:41731 --api-url http://127.0.0.1:18081
assert_url_rejected query \
  --web-url 'http://127.0.0.1:41731?probe=1' --api-url http://127.0.0.1:18081
assert_url_rejected fragment \
  --web-url 'http://127.0.0.1:41731#probe' --api-url http://127.0.0.1:18081

run_stub() {
  local project="$1" evidence="$2"; shift 2
  local timeout_seconds="${STUB_TIMEOUT_SECONDS:-20}"
  set +e
  STUB_OUTPUT="$(PATH="${FAKE_BIN}:${PATH}" \
    FAKE_DOCKER_MODE="${FAKE_DOCKER_MODE:-}" FAKE_DOCKER_LOG="${FAKE_DOCKER_LOG:-}" \
    FAKE_CURL_RECORD="${FAKE_CURL_RECORD:-}" FAKE_CURL_LOGIN_COUNT="${FAKE_CURL_LOGIN_COUNT:-}" \
    FAKE_CURL_STATUS_COUNT="${FAKE_CURL_STATUS_COUNT:-}" \
    FAKE_RUNNER_ID_INITIAL="${FAKE_RUNNER_ID_INITIAL:-}" \
    FAKE_RUNNER_ID_AFTER_RESTART="${FAKE_RUNNER_ID_AFTER_RESTART:-}" \
    FAKE_NPM_RECORD="${FAKE_NPM_RECORD:-}" \
    FAKE_SANITIZE_SENTINELS="${FAKE_SANITIZE_SENTINELS:-}" \
    FAKE_LEAK_FILE="${FAKE_LEAK_FILE:-}" FAKE_LEAK_SENTINEL="${FAKE_LEAK_SENTINEL:-}" \
    E2E_ADMIN_USERNAME="${E2E_ADMIN_USERNAME:-}" E2E_ADMIN_PASSWORD=f405-test-e2e-password \
    timeout "${timeout_seconds}s" bash "${HARNESS}" --compose-project "${project}" --evidence-root "${evidence}" "$@" 2>&1)"
  STUB_STATUS=$?
  set -e
  if [[ "${STUB_STATUS}" -eq 0 ]]; then
    echo "Compose 桩路径意外返回成功。" >&2
    exit 1
  fi
}

run_stub autotest-f405-explicit-url-test "${TMP_ROOT}/explicit" \
  --web-url http://127.0.0.1:41731 --api-url http://127.0.0.1:18081
if [[ "${STUB_OUTPUT}" == *"WebUrl 或 ApiUrl"* ]]; then
  echo "显式 WebUrl/ApiUrl 被错误判定为非法。" >&2
  exit 1
fi

run_stub autotest-f405-default-url-test "${TMP_ROOT}/default"
if [[ "${STUB_OUTPUT}" == *"WebUrl 或 ApiUrl"* ]]; then
  echo "默认 WebUrl/ApiUrl 被错误判定为非法。" >&2
  exit 1
fi

FAKE_DOCKER_MODE=resource-ls-fail
run_stub autotest-f405-resource-fail-test "${TMP_ROOT}/resource-fail"
if ! python3 - "${TMP_ROOT}/resource-fail/autotest-f405-resource-fail-test/results.json" <<'PY'
import json
import sys
from pathlib import Path
data = json.loads(Path(sys.argv[1]).read_text())
items = data.get("required", [])
if not any(item.get("id") == "preflight" and item.get("status") == "FAIL" for item in items):
    raise SystemExit(1)
PY
then
  echo "Docker 资源查询失败未形成 fail-closed 的 preflight FAIL。" >&2
  exit 1
fi

FAKE_DOCKER_MODE=up-fail
FAKE_DOCKER_LOG="${TMP_ROOT}/up-fail.docker.log"
: > "${FAKE_DOCKER_LOG}"
run_stub autotest-f405-up-fail-test "${TMP_ROOT}/up-fail"
if ! grep -q 'up --detach --build --wait' "${FAKE_DOCKER_LOG}" \
  || ! grep -q 'down --volumes --remove-orphans' "${FAKE_DOCKER_LOG}"; then
  echo "Compose up 部分失败后未执行同 project 的 down --volumes --remove-orphans。" >&2
  exit 1
fi

FAKE_DOCKER_MODE=inspect-fail
FAKE_DOCKER_LOG="${TMP_ROOT}/inspect-fail.docker.log"
run_stub autotest-f405-inspect-fail-test "${TMP_ROOT}/inspect-fail"
if grep -Eq '^compose ' "${FAKE_DOCKER_LOG}"; then
  echo "inspect 失败后不应继续执行 Compose config/pre-clean/build/up。" >&2
  exit 1
fi
if ! python3 - "${TMP_ROOT}/inspect-fail/autotest-f405-inspect-fail-test/results.json" <<'PY'
import json
import sys
from pathlib import Path
data = json.loads(Path(sys.argv[1]).read_text())
required = {item["id"]: item["status"] for item in data.get("required", [])}
if required.get("preflight") != "FAIL" or data.get("cleanup", {}).get("status") != "FAIL":
    raise SystemExit(1)
PY
then
  echo "inspect 失败未形成 preflight/cleanup FAIL。" >&2
  exit 1
fi

FAKE_DOCKER_MODE=auth-flow
FAKE_DOCKER_LOG="${TMP_ROOT}/auth-flow.docker.log"
FAKE_CURL_RECORD="${TMP_ROOT}/auth-flow.curl.log"
FAKE_CURL_LOGIN_COUNT="${TMP_ROOT}/auth-flow.login-count"
FAKE_CURL_STATUS_COUNT="${TMP_ROOT}/auth-flow.status-count"
FAKE_RUNNER_ID_INITIAL="00000000-0000-0000-0000-000000000099"
FAKE_RUNNER_ID_AFTER_RESTART="00000000-0000-0000-0000-000000000099"
FAKE_NPM_RECORD="${TMP_ROOT}/auth-flow.npm.log"
E2E_ADMIN_USERNAME="f405-external-admin@example.test"
run_stub autotest-f405-auth-flow-test "${TMP_ROOT}/auth-flow"
if ! grep -q 'login_count=1' "${FAKE_CURL_RECORD}" \
  || ! grep -q 'login_count=2' "${FAKE_CURL_RECORD}" \
  || [[ "$(grep -c '^login_count=' "${FAKE_CURL_RECORD}")" -ne 2 ]] \
  || ! grep -q 'login_username=f405-external-admin@example.test' "${FAKE_CURL_RECORD}" \
  || ! grep -q 'playwright_username=f405-external-admin@example.test' "${FAKE_NPM_RECORD}" \
  || ! grep -q 'runner_cookie=fake-session-1' "${FAKE_CURL_RECORD}" \
  || ! grep -q 'runner_cookie=fake-session-2' "${FAKE_CURL_RECORD}"; then
  echo "API 登录、Runner 状态和 Playwright 未统一使用管理员用户名/认证会话。" >&2
  exit 1
fi
if grep -q 'f405-test-e2e-password\|f405-test-admin-password' "${FAKE_CURL_RECORD}" "${FAKE_NPM_RECORD}"; then
  echo "桩记录不应包含登录凭据。" >&2
  exit 1
fi
AUTH_EVIDENCE="${TMP_ROOT}/auth-flow/autotest-f405-auth-flow-test"
if [[ -e "${AUTH_EVIDENCE}/session.cookie" || -e "${AUTH_EVIDENCE}/logs/login-before-run.json" ]]; then
  echo "cleanup 未删除 cookie/login 临时文件。" >&2
  exit 1
fi
if ! grep -q 'runner_status_count=2' "${FAKE_CURL_RECORD}" \
  || ! grep -q 'runner_status_count=3' "${FAKE_CURL_RECORD}" \
  || ! python3 - "${AUTH_EVIDENCE}/runner-status-before-restart.json" "${AUTH_EVIDENCE}/runner-status-after-restart.json" "${AUTH_EVIDENCE}/runner-container-started-at.txt" <<'PY'
import json
import sys
from pathlib import Path
before = json.loads(Path(sys.argv[1]).read_text())
after = json.loads(Path(sys.argv[2]).read_text())
started = Path(sys.argv[3]).read_text().strip()
if started != "2026-09-13T00:00:00.500000000Z":
    raise SystemExit(1)
if not any(item.get("runnerId") == "00000000-0000-0000-0000-000000000099"
           and item.get("lastSeenAt") == "2026-09-13T00:00:00Z"
           and item.get("status") == "ONLINE" for item in before):
    raise SystemExit(1)
items = after
if not any(item.get("runnerId") == "00000000-0000-0000-0000-000000000099"
           and item.get("lastSeenAt") == "2026-09-13T00:00:01Z"
           and item.get("status") == "ONLINE" for item in items):
    raise SystemExit(1)
PY
then
  echo "重启后未验证同一 Runner 的 lastSeenAt 严格推进。" >&2
  exit 1
fi
expected_runner_id="$(python3 - "autotest-f405-auth-flow-test" <<'PY'
import hashlib
import sys
digest = hashlib.sha256(sys.argv[1].encode()).hexdigest()
print(f"{digest[0:8]}-{digest[8:12]}-{digest[12:16]}-{digest[16:20]}-{digest[20:32]}")
PY
)"
if ! grep -q "runner_env_id=${expected_runner_id}" "${FAKE_DOCKER_LOG}"; then
  echo "F4-05 Compose 未按 project 派生并注入稳定 AUTOTEST_RUNNER_ID。" >&2
  exit 1
fi
if ! python3 - "${AUTH_EVIDENCE}/results.json" <<'PY'
import json
import sys
from pathlib import Path
data = json.loads(Path(sys.argv[1]).read_text())
if data.get("secretLeakSentinelCount") != 0:
    raise SystemExit(1)
PY
then
  echo "cleanup 泄漏扫描未通过。" >&2
  exit 1
fi

# 反例：重启后仅出现新 Runner ID，即使 lastSeenAt 更新也必须拒绝，不能把新实例误认成旧实例。
FAKE_DOCKER_MODE=auth-flow
FAKE_DOCKER_LOG="${TMP_ROOT}/runner-id-change.docker.log"
FAKE_CURL_RECORD="${TMP_ROOT}/runner-id-change.curl.log"
FAKE_CURL_LOGIN_COUNT="${TMP_ROOT}/runner-id-change.login-count"
FAKE_CURL_STATUS_COUNT="${TMP_ROOT}/runner-id-change.status-count"
FAKE_RUNNER_ID_INITIAL="00000000-0000-0000-0000-000000000099"
FAKE_RUNNER_ID_AFTER_RESTART="00000000-0000-0000-0000-000000000100"
E2E_ADMIN_USERNAME="f405-external-admin@example.test"
STUB_TIMEOUT_SECONDS=120
run_stub autotest-f405-runner-id-change-test "${TMP_ROOT}/runner-id-change"
RUNNER_ID_CHANGE_EVIDENCE="${TMP_ROOT}/runner-id-change/autotest-f405-runner-id-change-test"
if [[ "${STUB_STATUS}" -eq 0 ]] || ! python3 - "${RUNNER_ID_CHANGE_EVIDENCE}/results.json" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text())
required = {item["id"]: item["status"] for item in data.get("required", [])}
for ident in (
    "preflight",
    "compose_config",
    "pre_cleanup",
    "compose_build",
    "compose_start",
    "health_after_start",
    "runner_heartbeat",
    "browser_api_case",
    "browser_suite",
):
    if required.get(ident) != "PASS":
        raise SystemExit(f"negative case 前置门禁未完成：{ident}={required.get(ident)!r}")
if required.get("restart_report_recovery") != "FAIL":
    raise SystemExit(1)
PY
then
  echo "Runner ID 在重启后变化时必须让 results.json 的 restart_report_recovery=FAIL。" >&2
  exit 1
fi
unset STUB_TIMEOUT_SECONDS

FAKE_DOCKER_MODE=up-fail
FAKE_DOCKER_LOG="${TMP_ROOT}/up-fail-order.docker.log"
: > "${FAKE_DOCKER_LOG}"
run_stub autotest-f405-up-fail-order-test "${TMP_ROOT}/up-fail-order"
if ! python3 - "${FAKE_DOCKER_LOG}" "${TMP_ROOT}/up-fail-order/autotest-f405-up-fail-order-test/results.json" <<'PY'
import json
import sys
from pathlib import Path

lines = Path(sys.argv[1]).read_text().splitlines()
up = [i for i, line in enumerate(lines) if line.startswith("compose ") and " up --detach --build --wait" in line]
down = [i for i, line in enumerate(lines) if line.startswith("compose ") and " down --volumes --remove-orphans" in line]
results = json.loads(Path(sys.argv[2]).read_text())
if len(up) != 1 or len(down) < 2:
    raise SystemExit(1)
if not any(index < up[0] for index in down) or not any(index > up[0] for index in down):
    raise SystemExit(1)
if results.get("cleanup", {}).get("status") != "PASS":
    raise SystemExit(1)
PY
then
  echo "up 部分失败后的 down 顺序、次数或 cleanup 状态不符合门禁。" >&2
  exit 1
fi

# mode=1 脱敏正例：混合文本、压缩 JSON 和至少两条 NDJSON 必须实际经过
# 验收入口，最终证据中只保留 [REDACTED]，不能残留任何动态哨兵。
FAKE_DOCKER_MODE=auth-flow
FAKE_DOCKER_LOG="${TMP_ROOT}/sanitize-mode1.docker.log"
FAKE_CURL_RECORD="${TMP_ROOT}/sanitize-mode1.curl.log"
FAKE_CURL_LOGIN_COUNT="${TMP_ROOT}/sanitize-mode1.login-count"
FAKE_CURL_STATUS_COUNT="${TMP_ROOT}/sanitize-mode1.status-count"
FAKE_NPM_RECORD="${TMP_ROOT}/sanitize-mode1.npm.log"
FAKE_SANITIZE_SENTINELS=1
unset FAKE_LEAK_FILE FAKE_LEAK_SENTINEL
E2E_ADMIN_USERNAME="f405-external-admin@example.test"
run_stub autotest-f405-sanitize-mode1-test "${TMP_ROOT}/sanitize-mode1" --keep-evidence
SANITIZE_MODE1_EVIDENCE="${TMP_ROOT}/sanitize-mode1/autotest-f405-sanitize-mode1-test"
if ! python3 - "${SANITIZE_MODE1_EVIDENCE}" <<'PY'
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
log_files = sorted(root.glob("logs/browser_*.log"))
parts = [path.read_text(errors="replace") for path in log_files]
text = "\n".join(parts)
sentinels = [
    "dynamic-auth-header-sentinel",
    "dynamic-json-authorization-sentinel",
    "dynamic-json-token-sentinel",
    "dynamic-json-cookie-sentinel",
    "dynamic-json-password-sentinel",
    "dynamic-json-secret-sentinel",
    "dynamic-json-key-sentinel",
    "dynamic-header-token-sentinel",
    "dynamic-header-api-key-sentinel",
    "dynamic-header-bearer-sentinel",
    "dynamic-ndjson-authorization-sentinel",
    "dynamic-ndjson-token-sentinel",
    "dynamic-ndjson-api-key-sentinel",
    "dynamic-ndjson-password-sentinel",
]
objects = []
for part in parts:
    for line in part.splitlines():
        candidate = line.strip()
        if not candidate.startswith("{"):
            continue
        try:
            parsed = json.loads(candidate)
        except json.JSONDecodeError as exc:
            raise SystemExit(f"mode=1 JSON/NDJSON 结构损坏: {exc}")
        if not isinstance(parsed, dict):
            raise SystemExit("mode=1 NDJSON 对象类型不正确")
        objects.append(parsed)
if len(objects) < 2:
    raise SystemExit("mode=1 至少需要解析出两条 NDJSON/JSON 对象")
if not any(item.get("authorization") == "[REDACTED]" for item in objects):
    raise SystemExit("mode=1 authorization 字段未保留结构并完成脱敏")
if not any(isinstance(item.get("headers"), dict) for item in objects):
    raise SystemExit("mode=1 headers 结构未保留")
if "[REDACTED]" not in text or any(sentinel in text for sentinel in sentinels):
    raise SystemExit(1)
PY
then
  echo "mode=1 混合文本、压缩 JSON 或 NDJSON 脱敏断言未通过。" >&2
  exit 1
fi

FAKE_DOCKER_MODE=auth-flow
FAKE_DOCKER_LOG="${TMP_ROOT}/leak-flow.docker.log"
FAKE_CURL_RECORD="${TMP_ROOT}/leak-flow.curl.log"
FAKE_CURL_LOGIN_COUNT="${TMP_ROOT}/leak-flow.login-count"
FAKE_CURL_STATUS_COUNT="${TMP_ROOT}/leak-flow.status-count"
FAKE_NPM_RECORD="${TMP_ROOT}/leak-flow.npm.log"
FAKE_SANITIZE_SENTINELS=2
FAKE_LEAK_FILE="${TMP_ROOT}/leak-flow/autotest-f405-leak-flow-test/leak-sentinel.log"
FAKE_LEAK_SENTINEL=f405-test-db-password
E2E_ADMIN_USERNAME="f405-external-admin@example.test"
run_stub autotest-f405-leak-flow-test "${TMP_ROOT}/leak-flow" --keep-evidence
LEAK_EVIDENCE="${TMP_ROOT}/leak-flow/autotest-f405-leak-flow-test"
if ! python3 - "${LEAK_EVIDENCE}/results.json" <<'PY'
import json
import sys
from pathlib import Path
data = json.loads(Path(sys.argv[1]).read_text())
if data.get("secretLeakSentinelCount", 0) <= 0 or data.get("overall") != "FAIL":
    raise SystemExit(1)
PY
then
  echo "泄漏哨兵未使 secretLeakSentinelCount>0 且总结果 FAIL。" >&2
  exit 1
fi
if grep -R -F --binary-files=without-match --quiet -- "${FAKE_LEAK_SENTINEL}" "${LEAK_EVIDENCE}"; then
  echo "最终保留的 results/证据目录仍包含泄漏哨兵明文。" >&2
  exit 1
fi
for dynamic_sentinel in \
  dynamic-auth-header-sentinel \
  dynamic-json-authorization-sentinel \
  dynamic-json-token-sentinel \
  dynamic-json-cookie-sentinel \
  dynamic-json-password-sentinel \
  dynamic-json-secret-sentinel \
  dynamic-json-key-sentinel \
  dynamic-header-token-sentinel \
  dynamic-header-api-key-sentinel \
  dynamic-header-bearer-sentinel \
  dynamic-pretty-json-authorization-sentinel \
  dynamic-pretty-json-token-sentinel \
  dynamic-pretty-json-cookie-sentinel \
  dynamic-pretty-json-password-sentinel \
  dynamic-pretty-json-secret-sentinel \
  dynamic-pretty-json-key-sentinel \
  dynamic-pretty-json-nested-authorization-sentinel; do
  if grep -R -F --binary-files=without-match --quiet -- "${dynamic_sentinel}" "${LEAK_EVIDENCE}"; then
    echo "最终保留的证据仍包含未知动态凭据哨兵：${dynamic_sentinel}" >&2
    exit 1
  fi
done

echo "F4-05 验收入口缺少 deployment/.env 的 fail-fast 检查通过。"
