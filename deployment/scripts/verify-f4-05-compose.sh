#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# F4-05 全新 Compose 验收入口。
# 该脚本只操作显式校验过的 Compose project，不使用 docker prune、固定容器名或固定端口。

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/deployment/docker-compose.yml"
ENV_FILE="${ROOT_DIR}/deployment/.env"

PROJECT="autotest-f405-$(date -u +%Y%m%d%H%M%S)-${RANDOM}"
WEB_URL=""
API_URL=""
EVIDENCE_ROOT="${ROOT_DIR}/.superpowers/f4-05-evidence"
KEEP_EVIDENCE=false

usage() {
  cat <<'EOF'
用法：verify-f4-05-compose.sh [选项]

  --compose-project <name>  专用 Compose project，必须匹配 autotest-f405-[a-z0-9-]+
  --web-url <url>           Web 地址，例如 http://127.0.0.1:4173
  --api-url <url>           Platform API 地址，例如 http://127.0.0.1:8080
  --evidence-root <path>    验收证据根目录，必须位于当前仓库内
  --keep-evidence           清理服务/卷，但保留本次脱敏证据目录
  -h, --help                显示帮助
EOF
}

missing_option_value() {
  echo "参数 ${1} 缺少值。" >&2
  exit 2
}

while (($# > 0)); do
  case "$1" in
    --compose-project)
      if (($# < 2)) || [[ -z "$2" || "$2" == --* ]]; then missing_option_value "$1"; fi
      PROJECT="$2"; shift 2 ;;
    --web-url)
      if (($# < 2)) || [[ -z "$2" || "$2" == --* ]]; then missing_option_value "$1"; fi
      WEB_URL="$2"; shift 2 ;;
    --api-url)
      if (($# < 2)) || [[ -z "$2" || "$2" == --* ]]; then missing_option_value "$1"; fi
      API_URL="$2"; shift 2 ;;
    --evidence-root)
      if (($# < 2)) || [[ -z "$2" || "$2" == --* ]]; then missing_option_value "$1"; fi
      EVIDENCE_ROOT="$2"; shift 2 ;;
    --keep-evidence) KEEP_EVIDENCE=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数：$1" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ ! "${PROJECT}" =~ ^autotest-f405-[a-z0-9-]+$ ]]; then
  echo "Compose project 名称不合法，必须匹配 autotest-f405-[a-z0-9-]+。" >&2
  exit 2
fi

# F4-05 的 Runner 身份只在本次专用验收进程中注入，并由 Compose project 派生。
# 不写入普通 Compose 默认值，避免不同验收 project 共用 Runner UUID。
AUTOTEST_RUNNER_ID="$(python3 - "${PROJECT}" <<'PY'
import hashlib
import sys

digest = hashlib.sha256(sys.argv[1].encode("utf-8")).hexdigest()
print(f"{digest[0:8]}-{digest[8:12]}-{digest[12:16]}-{digest[16:20]}-{digest[20:32]}")
PY
)"
export AUTOTEST_RUNNER_ID

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "缺少 deployment/.env；脚本不会从日志或仓库读取敏感配置。" >&2
  exit 2
fi

if [[ -z "${E2E_ADMIN_PASSWORD:-}" ]]; then
  echo "请通过 E2E_ADMIN_PASSWORD 提供验收凭据；脚本不会从日志或仓库读取敏感配置。" >&2
  exit 2
fi

if [[ ! -f "${COMPOSE_FILE}" ]]; then
  echo "缺少 deployment/docker-compose.yml。" >&2
  exit 2
fi

repo_real="$(realpath -e "${ROOT_DIR}")"
assert_repo_path() {
  local candidate
  candidate="$(realpath -m "$1")"
  case "${candidate}" in
    "${repo_real}"|"${repo_real}"/*) ;;
    *) echo "路径越过仓库边界：${candidate}" >&2; exit 2 ;;
  esac
}
assert_repo_path "${COMPOSE_FILE}"
assert_repo_path "${ENV_FILE}"
assert_repo_path "${EVIDENCE_ROOT}"

env_value_present() {
  local key="$1"
  awk -F= -v key="${key}" '
    $1 == key { value=$0; sub(/^[^=]*=/, "", value); found=(value != "" && value !~ /^请替换/); }
    END { exit(found ? 0 : 1) }
  ' "${ENV_FILE}"
}

for required_key in AUTOTEST_DB_NAME AUTOTEST_DB_USERNAME AUTOTEST_DB_PASSWORD \
  AUTOTEST_ADMIN_USERNAME AUTOTEST_ADMIN_PASSWORD AUTOTEST_MASTER_KEY AUTOTEST_RUNNER_CALLBACK_TOKEN; do
  if ! env_value_present "${required_key}"; then
    echo "deployment/.env 缺少有效的 ${required_key} 配置。" >&2
    exit 2
  fi
done

ADMIN_USERNAME="${E2E_ADMIN_USERNAME:-}"
if [[ -z "${ADMIN_USERNAME}" ]]; then
  ADMIN_USERNAME="$(awk -F= '$1 == "AUTOTEST_ADMIN_USERNAME" {sub(/^[^=]*=/, ""); print; exit}' "${ENV_FILE}")"
fi
if [[ -z "${ADMIN_USERNAME}" ]]; then
  echo "无法确定统一的管理员用户名来源。" >&2
  exit 2
fi

validate_url() {
  python3 - "$1" <<'PY'
import sys
from urllib.parse import urlsplit

value = sys.argv[1]
parts = urlsplit(value)
if parts.scheme not in {"http", "https"} or parts.hostname not in {"localhost", "127.0.0.1", "::1"} or parts.username or parts.password:
    raise SystemExit(1)
if parts.query or parts.fragment:
    raise SystemExit(1)
try:
    if parts.port is None or not (1 <= parts.port <= 65535):
        raise SystemExit(1)
except ValueError:
    raise SystemExit(1)
PY
}

free_port() {
  python3 - <<'PY'
import socket
with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}

if [[ -z "${WEB_URL}" ]]; then WEB_URL="http://127.0.0.1:$(free_port)"; fi
if [[ -z "${API_URL}" ]]; then API_URL="http://127.0.0.1:$(free_port)"; fi
if ! validate_url "${WEB_URL}" || ! validate_url "${API_URL}"; then
  echo "WebUrl 或 ApiUrl 必须是无凭据、无 query/fragment 的 HTTP(S) 地址。" >&2
  exit 2
fi

url_port() {
  python3 -c 'import sys; from urllib.parse import urlsplit; print(urlsplit(sys.argv[1]).port)' "$1"
}

WEB_PORT="$(url_port "${WEB_URL}")"
API_PORT="$(url_port "${API_URL}")"
if [[ "${WEB_PORT}" == "${API_PORT}" ]]; then
  echo "WebUrl 与 ApiUrl 不能共用同一个宿主端口。" >&2
  exit 2
fi

EVIDENCE_DIR="$(realpath -m "${EVIDENCE_ROOT}/${PROJECT}")"
assert_repo_path "${EVIDENCE_DIR}"
mkdir -p -- "${EVIDENCE_DIR}/logs"
COMMAND_LOG="${EVIDENCE_DIR}/commands.log"
RESULT_TSV="${EVIDENCE_DIR}/results.tsv"
: > "${COMMAND_LOG}"
printf 'id\tstatus\treason\n' > "${RESULT_TSV}"

export AUTOTEST_API_PORT="${API_PORT}"
export AUTOTEST_WEB_PORT="${WEB_PORT}"
COMPOSE=(docker compose --project-name "${PROJECT}" --env-file "${ENV_FILE}" --file "${COMPOSE_FILE}")
RUN_FAILED=0
STARTED=0
COMPOSE_UP_ATTEMPTED=0
PRE_FLIGHT_OK=0
PROJECT_BOUNDARY_SAFE=0
CLEANUP_STATUS="PENDING"
SESSION_COOKIE="${EVIDENCE_DIR}/session.cookie"

sanitize_file() {
  local file="$1"
  [[ -f "${file}" ]] || return 0
  python3 - "${file}" <<'PY'
import json
import re
import sys
from pathlib import Path

path = Path(sys.argv[1])
text = path.read_text(errors="replace")

REDACTED = "[REDACTED]"


def sensitive_name(value):
    """Return whether a JSON key or HTTP header name carries a secret."""
    if not isinstance(value, str):
        return False
    normalized = re.sub(r"[^a-z0-9]", "", value.lower())
    return (
        normalized in {
            "authorization", "token", "accesstoken", "refreshtoken",
            "cookie", "password", "secret", "masterkey", "apikey",
            "privatekey", "key",
        }
        or normalized.endswith(("authorization", "token", "cookie", "password", "secret", "key"))
    )


def header_name(value):
    """Read a header pair name without depending on JSON key casing."""
    if not isinstance(value, dict):
        return None
    for key, item in value.items():
        if isinstance(key, str) and key.lower() == "name":
            return item
    return None


def redact_plain_text(value):
    """Redact an unstructured text segment without touching JSON fragments."""
    # Run the generic key/value rules first.  Authorization is deliberately
    # last: otherwise the generic rule sees the closing bracket in
    # ``[REDACTED]`` as a delimiter and leaves an extra ``]`` behind.
    def redact_header(match):
        name = match.group("name").strip("\"'")
        return redact_match(match) if sensitive_name(name) else match.group(0)

    def redact_field(match):
        return redact_match(match) if sensitive_name(match.group("name")) else match.group(0)

    value = header_pair.sub(redact_header, value)
    value = sensitive_field.sub(redact_field, value)
    value = environment_secret.sub(redact_match, value)
    return authorization_line.sub(
        lambda match: f"{match.group('prefix')}{REDACTED}", value
    )


def redact_mixed_text(value):
    """Redact mixed text while keeping every complete JSON fragment valid."""
    decoder = json.JSONDecoder()
    chunks = []
    plain = []

    def flush_plain():
        if plain:
            chunks.append(redact_plain_text("".join(plain)))
            plain.clear()

    index = 0
    while index < len(value):
        if value[index] in "[{":
            try:
                parsed, end = decoder.raw_decode(value, index)
            except (TypeError, ValueError):
                parsed = None
                end = index
            if isinstance(parsed, (dict, list)) and end > index:
                flush_plain()
                fragment = value[index:end]
                formatting = {"ensure_ascii": False}
                if "\n" in fragment or "\r" in fragment:
                    formatting["indent"] = 2
                else:
                    formatting["separators"] = (",", ":")
                chunks.append(json.dumps(redact_json(parsed), **formatting))
                index = end
                continue
        plain.append(value[index])
        index += 1
    flush_plain()
    return "".join(chunks)


def redact_text(value):
    """Redact credentials embedded in a scalar string."""
    if not isinstance(value, str):
        return value

    # A scalar may contain a complete JSON object/array (for example a
    # payload kept as a string).  Parse it before falling back to text rules.
    candidate = value.strip()
    if candidate and candidate[:1] in "[{":
        try:
            nested = json.loads(candidate)
        except (TypeError, ValueError):
            nested = None
        if isinstance(nested, (dict, list)):
            # Keep the surrounding whitespace/newline.  The non-JSON fallback
            # processes NDJSON one line at a time; dropping the line ending
            # would concatenate adjacent JSON objects and make the evidence
            # unreadable on the next health/report parse.
            leading = value[: len(value) - len(value.lstrip())]
            trailing = value[len(value.rstrip()) :]
            sanitized = json.dumps(
                redact_json(nested), ensure_ascii=False, separators=(",", ":")
            )
            return f"{leading}{sanitized}{trailing}"

    # Mixed command output may contain plain lines and one or more compact or
    # pretty JSON objects.  Sanitize them as separate segments so the plain
    # text regexes cannot corrupt already-serialized JSON.
    return redact_mixed_text(value)


def redact_json(value):
    """Recursively redact JSON, including JSON encoded inside string values."""
    if isinstance(value, dict):
        result = {}
        pair_name = header_name(value)
        for key, item in value.items():
            if isinstance(key, str) and sensitive_name(key):
                result[key] = REDACTED
            elif isinstance(key, str) and key.lower() == "value" and sensitive_name(pair_name):
                result[key] = REDACTED
            else:
                result[key] = redact_json(item) if isinstance(item, (dict, list)) else redact_text(item)
        return result
    if isinstance(value, list):
        return [redact_json(item) for item in value]
    return redact_text(value)


def redact_match(match):
    prefix = match.group("prefix")
    value = match.group("value")
    quote = value[:1] if value[:1] in {"\"", "'"} else ""
    return f"{prefix}{quote}{REDACTED}{quote}" if quote else f"{prefix}{REDACTED}"


# Plain HTTP header lines may use any authentication scheme. Redact the whole
# value (including the scheme) so custom schemes cannot leave a credential.
authorization_line = re.compile(
    r"(?im)(?P<prefix>(?<![\w-])[\"']?Authorization[\"']?\s*[:=]\s*)(?P<value>[^\r\n]*)"
)

# Fallback for non-JSON text containing key/value output. JSON lines are parsed
# structurally above; this covers shell logs and pretty-printed fragments.
header_pair = re.compile(
    r"(?i)(?P<prefix>[\"']?name[\"']?\s*[:=]\s*"
    r"(?P<name>[\"'][^\"']*[\"']|[^\s,}\]]+)\s*,\s*"
    r"[\"']?value[\"']?\s*[:=]\s*)"
    r"(?P<value>\"[^\"]*\"|'[^']*'|[^\s,}\]]+)"
)
sensitive_field = re.compile(
    r"(?i)(?P<prefix>[\"']?(?P<name>[\w-]+)[\"']?\s*[:=]\s*)"
    r"(?P<value>\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'|[^\s,;}\]]+)"
)
environment_secret = re.compile(
    r"(?i)(?P<prefix>(?:E2E_ADMIN_PASSWORD|AUTOTEST_[A-Z0-9_]*(?:PASSWORD|TOKEN|KEY))\s*=\s*)"
    r"(?P<value>\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'|[^\s]+)"
)

# Prefer parsing the complete file.  This is required for pretty-printed JSON,
# where a header's name and value are on different lines.  If the whole file
# is not JSON (for example a mixed command log), scan complete JSON fragments
# across line boundaries while retaining the original non-sensitive content.
try:
    parsed = json.loads(text)
except (TypeError, ValueError):
    # Keep mixed text intact while recursively sanitizing any complete JSON
    # fragments found across line boundaries.
    path.write_text(redact_text(text))
else:
    path.write_text(json.dumps(redact_json(parsed), ensure_ascii=False, indent=2) + "\n")
PY
}

redact_secret_in_evidence() {
  local secret="$1" file redaction_status=0
  [[ -n "${secret}" ]] || return 0
  local -a evidence_files=()
  while IFS= read -r -d '' file; do
    evidence_files+=("${file}")
  done < <(find "${EVIDENCE_DIR}" -type f -print0)
  for file in "${evidence_files[@]}"; do
    if grep -F --binary-files=without-match --quiet -- "${secret}" "${file}"; then
      if ! REDACT_SECRET="${secret}" python3 - "${file}" <<'PY'
import os
import sys
from pathlib import Path

path = Path(sys.argv[1])
secret = os.environ["REDACT_SECRET"].encode()
data = path.read_bytes()
path.write_bytes(data.replace(secret, b"[REDACTED]"))
PY
      then
        redaction_status=1
      fi
    fi
  done
  return "${redaction_status}"
}

record_result() {
  local id="$1" status="$2" reason="$3"
  reason="${reason//$'\t'/ }"
  reason="${reason//$'\n'/ }"
  printf '%s\t%s\t%s\n' "${id}" "${status}" "${reason}" >> "${RESULT_TSV}"
}

record_command() {
  local id="$1"; shift
  local output_file="${EVIDENCE_DIR}/logs/${id}.log"
  set +e
  "$@" > "${output_file}" 2>&1
  local status=$?
  set -e
  sanitize_file "${output_file}"
  printf '%s\texit=%s\n' "${id}" "${status}" >> "${COMMAND_LOG}"
  return "${status}"
}

capture_command() {
  local id="$1" output_file="$2"; shift 2
  set +e
  "$@" > "${output_file}" 2>&1
  local status=$?
  set -e
  sanitize_file "${output_file}"
  printf '%s\texit=%s\n' "${id}" "${status}" >> "${COMMAND_LOG}"
  return "${status}"
}

capture_runner_started_at() {
  local container_id_file="${EVIDENCE_DIR}/runner-container-id.txt"
  local started_at_file="${EVIDENCE_DIR}/runner-container-started-at.txt"
  local container_id
  if ! capture_command runner-container-id "${container_id_file}" \
      "${COMPOSE[@]}" ps -q runner-app; then
    return 1
  fi
  container_id="$(tr -d '[:space:]' < "${container_id_file}")"
  if [[ ! "${container_id}" =~ ^[[:alnum:]]{12,64}$ ]]; then
    return 1
  fi
  if ! capture_command runner-container-started-at "${started_at_file}" \
      docker inspect --format '{{.State.StartedAt}}' "${container_id}"; then
    return 1
  fi
  python3 - "${started_at_file}" <<'PY'
import sys
from datetime import datetime
from pathlib import Path

value = Path(sys.argv[1]).read_text(errors="replace").strip()
try:
    datetime.fromisoformat(value.replace("Z", "+00:00"))
except ValueError:
    raise SystemExit(1)
PY
}

resource_ids() {
  local kind="$1"
  case "${kind}" in
    container)
      docker "${kind}" ls -aq --filter "label=com.docker.compose.project=${PROJECT}" || return 1
      ;;
    network)
      docker network ls -q --filter "label=com.docker.compose.project=${PROJECT}" || return 1
      ;;
    volume)
      docker volume ls -q --filter "label=com.docker.compose.project=${PROJECT}" || return 1
      ;;
    *) return 2 ;;
  esac
}

verify_project_boundaries() {
  local kind id label ids
  for kind in container volume network; do
    if ! ids="$(resource_ids "${kind}")"; then
      return 1
    fi
    while IFS= read -r id; do
      [[ -n "${id}" ]] || continue
      if [[ "${kind}" == "container" ]]; then
        if ! label="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "${id}")"; then
          return 1
        fi
      else
        if ! label="$(docker inspect --format '{{ index .Labels "com.docker.compose.project" }}' "${id}")"; then
          return 1
        fi
      fi
      [[ "${label}" == "${PROJECT}" ]] || return 1
    done <<< "${ids}"
  done
}

verify_no_project_resources() {
  local kind count ids
  for kind in container volume network; do
    if ! ids="$(resource_ids "${kind}")"; then
      return 1
    fi
    count="$(printf '%s\n' "${ids}" | sed '/^$/d' | wc -l)"
    [[ "${count}" -eq 0 ]] || return 1
  done
}

compose_ps_snapshot() {
  local output_file="$1"
  set +e
  "${COMPOSE[@]}" ps --all --format json > "${output_file}" 2>&1
  local status=$?
  set -e
  sanitize_file "${output_file}"
  printf 'compose-ps-%s\texit=%s\n' "$(basename "${output_file}")" "${status}" >> "${COMMAND_LOG}"
  return "${status}"
}

healthy_snapshot() {
  python3 - "$1" <<'PY'
import json
import sys
from pathlib import Path

raw = Path(sys.argv[1]).read_text(errors="replace").strip()
if not raw:
    raise SystemExit(1)
try:
    value = json.loads(raw)
    items = value if isinstance(value, list) else [value]
except json.JSONDecodeError:
    items = [json.loads(line) for line in raw.splitlines() if line.strip()]
expected = {"postgres", "platform-api", "web", "runner-app"}
actual = {item.get("Service") for item in items if isinstance(item, dict)}
if actual != expected:
    raise SystemExit(1)
for item in items:
    if item.get("State", "").lower() != "running" or item.get("Health", "").lower() != "healthy":
        raise SystemExit(1)
PY
}

wait_healthy() {
  local snapshot="$1"
  local attempt
  for attempt in $(seq 1 60); do
    if compose_ps_snapshot "${snapshot}" && healthy_snapshot "${snapshot}"; then return 0; fi
    sleep 1
  done
  return 1
}

runner_online() {
  local cookie="$1" output_file="$2"
  if ! curl --fail --silent --show-error --max-time 10 --cookie "${cookie}" \
    "${API_URL%/}/api/v1/runners/status" > "${output_file}" 2>&1; then
    sanitize_file "${output_file}"
    return 1
  fi
  sanitize_file "${output_file}"
  python3 - "${output_file}" <<'PY'
import json
import sys
from pathlib import Path
items = json.loads(Path(sys.argv[1]).read_text())
if not isinstance(items, list) or not any(item.get("status") == "ONLINE" for item in items):
    raise SystemExit(1)
PY
}

runner_online_after_baseline() {
  local cookie="$1" baseline_file="$2" started_at_file="$3" output_file="$4"
  if ! curl --fail --silent --show-error --max-time 10 --cookie "${cookie}" \
    "${API_URL%/}/api/v1/runners/status" > "${output_file}" 2>&1; then
    sanitize_file "${output_file}"
    return 1
  fi
  sanitize_file "${output_file}"
  python3 - "${output_file}" "${baseline_file}" "${started_at_file}" <<'PY'
import json
import sys
from datetime import datetime
from pathlib import Path

def instant(value):
    if not isinstance(value, str) or not value:
        raise ValueError(value)
    return datetime.fromisoformat(value.replace("Z", "+00:00"))

current = json.loads(Path(sys.argv[1]).read_text())
baseline = json.loads(Path(sys.argv[2]).read_text())
restart_started = instant(Path(sys.argv[3]).read_text().strip())
baseline_items = baseline if isinstance(baseline, list) else []
current_items = current if isinstance(current, list) else []
baseline_online = next(
    (item for item in baseline_items
     if item.get("status") == "ONLINE" and item.get("runnerId") and item.get("lastSeenAt")),
    None,
)
if baseline_online is None:
    raise SystemExit(1)
runner_id = baseline_online["runnerId"]
baseline_seen = instant(baseline_online["lastSeenAt"])
for item in current_items:
    if item.get("runnerId") != runner_id or item.get("status") != "ONLINE":
        continue
    try:
        current_seen = instant(item.get("lastSeenAt"))
        if current_seen > baseline_seen and current_seen > restart_started:
            raise SystemExit(0)
    except (TypeError, ValueError):
        continue
raise SystemExit(1)
PY
}

wait_runner_online() {
  local cookie="$1" output_file="$2" attempt
  for attempt in $(seq 1 45); do
    if runner_online "${cookie}" "${output_file}"; then return 0; fi
    sleep 1
  done
  return 1
}

wait_runner_online_after_baseline() {
  local cookie="$1" baseline_file="$2" started_at_file="$3" output_file="$4" attempt
  for attempt in $(seq 1 45); do
    if runner_online_after_baseline "${cookie}" "${baseline_file}" "${started_at_file}" "${output_file}"; then return 0; fi
    sleep 1
  done
  return 1
}

validate_meta() {
  python3 - "$1" <<'PY'
import json
import re
import sys
from pathlib import Path
value = json.loads(Path(sys.argv[1]).read_text())
for key in ("projectId", "runId"):
    if not re.fullmatch(r"[0-9a-fA-F-]{36}", str(value.get(key, ""))):
        raise SystemExit(1)
PY
}

run_browser_spec() {
  local id="$1" spec="$2" meta="$3" expect_suite="$4"
  rm -f -- "${meta}"
  set +e
  E2E_BASE_URL="${WEB_URL}" E2E_ADMIN_PASSWORD="${E2E_ADMIN_PASSWORD}" \
    E2E_ADMIN_USERNAME="${ADMIN_USERNAME}" F1_10_RUN_META_FILE="${meta}" \
    E2E_EXPECT_SUITE_REPORT="${expect_suite}" \
    npm --prefix "${ROOT_DIR}/web" exec -- playwright test "${spec}" --workers=1 --reporter=line \
    > "${EVIDENCE_DIR}/logs/${id}.log" 2>&1
  local status=$?
  set -e
  sanitize_file "${EVIDENCE_DIR}/logs/${id}.log"
  printf '%s\texit=%s\n' "${id}" "${status}" >> "${COMMAND_LOG}"
  if [[ "${status}" -ne 0 || ! -f "${meta}" ]] || ! validate_meta "${meta}"; then
    return 1
  fi
  return 0
}

read_meta_field() {
  python3 - "$1" "$2" <<'PY'
import json
import sys
from pathlib import Path
print(json.loads(Path(sys.argv[1]).read_text()).get(sys.argv[2], ""))
PY
}

api_login() {
  local cookie="$1" output="$2"
  local body
  body="$(python3 - "${ADMIN_USERNAME}" "${E2E_ADMIN_PASSWORD}" <<'PY'
import json
import sys
print(json.dumps({"username": sys.argv[1], "password": sys.argv[2]}))
PY
)"
  set +e
  printf '%s' "${body}" | curl --fail --silent --show-error --cookie-jar "${cookie}" \
    --header 'Content-Type: application/json' --data-binary @- \
    "${API_URL%/}/api/v1/auth/login" > "${output}" 2>&1
  local status=$?
  set -e
  sanitize_file "${output}"
  printf 'api-login\texit=%s\n' "${status}" >> "${COMMAND_LOG}"
  return "${status}"
}

check_report() {
  local cookie="$1" meta="$2" output="$3" require_suite="$4"
  local project_id run_id
  project_id="$(read_meta_field "${meta}" projectId)"
  run_id="$(read_meta_field "${meta}" runId)"
  set +e
  curl --fail --silent --show-error --cookie "${cookie}" \
    "${API_URL%/}/api/v1/projects/${project_id}/runs/${run_id}/report" > "${output}" 2>&1
  local status=$?
  set -e
  sanitize_file "${output}"
  printf 'report-%s\texit=%s\n' "$(basename "${meta}")" "${status}" >> "${COMMAND_LOG}"
  [[ "${status}" -eq 0 ]] || return 1
  python3 - "${output}" "${require_suite}" <<'PY'
import json
import sys
from pathlib import Path
report = json.loads(Path(sys.argv[1]).read_text())
if report.get("status") != "PASSED" or len(report.get("steps", [])) < 1:
    raise SystemExit(1)
if sys.argv[2] == "true" and len(report.get("suiteMembers", [])) < 1:
    raise SystemExit(1)
PY
}

write_results() {
  local overall="$1" cleanup="$2" leak_count="$3"
  python3 - "${RESULT_TSV}" "${EVIDENCE_DIR}/results.json" "${PROJECT}" "${WEB_URL}" "${API_URL}" "${overall}" "${cleanup}" "${leak_count}" <<'PY'
import json
import sys
from pathlib import Path

tsv, output, project, web, api, overall, cleanup, leak_count = sys.argv[1:]
required = []
blocked = []
for line in Path(tsv).read_text().splitlines()[1:]:
    if not line.strip(): continue
    ident, status, reason = line.split("\t", 2)
    item = {"id": ident, "status": status, "reason": reason}
    if status == "BLOCKED": blocked.append(item)
    else: required.append(item)
payload = {
    "schemaVersion": 1,
    "composeProject": project,
    "webUrl": web,
    "apiUrl": api,
    "overall": overall,
    "required": required,
    "blocked": blocked,
    "cleanup": {"status": cleanup},
    "secretLeakSentinelCount": int(leak_count),
    "evidenceDirectory": str(Path(output).parent),
}
Path(output).write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
PY
}

write_record() {
  local results_file="$1"
  python3 - "${results_file}" "${EVIDENCE_DIR}/F4-05-最终验收记录.md" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text())
lines = [
    "# F4-05 全新 WSL Compose 最终验收记录", "",
    f"- Compose project：`{data['composeProject']}`",
    f"- WebUrl：`{data['webUrl']}`",
    f"- ApiUrl：`{data['apiUrl']}`",
    f"- 总结果：**{data['overall']}**",
    f"- 脱敏哨兵泄漏数：`{data['secretLeakSentinelCount']}`",
    f"- 专用资源清理：**{data['cleanup']['status']}**", "",
    "## 必须门禁", "", "| 项目 | 状态 | 说明 |", "|---|---|---|",
]
for item in data["required"]:
    lines.append(f"| `{item['id']}` | **{item['status']}** | {item['reason']} |")
lines += ["", "## BLOCKED / NOT_IMPLEMENTED", "", "| 能力 | 状态 | 原因 |", "|---|---|---|"]
for item in data["blocked"]:
    lines.append(f"| `{item['id']}` | **{item['status']}** | {item['reason']} |")
lines += ["", "## 证据文件", "", "- `results.json`：机器可读汇总。",
          "- `commands.log`：每条命令的退出码，不记录命令敏感参数。",
          "- `logs/`：经过敏感字段脱敏的命令和 Playwright 日志。", ""]
Path(sys.argv[2]).write_text("\n".join(lines))
PY
}

cleanup() {
  local exit_code="$1"
  set +e
  local down_status=0 boundary_status=0
  if [[ "${COMPOSE_UP_ATTEMPTED}" -eq 1 ]]; then
    "${COMPOSE[@]}" down --volumes --remove-orphans > "${EVIDENCE_DIR}/logs/cleanup-down.log" 2>&1
    down_status=$?
    sanitize_file "${EVIDENCE_DIR}/logs/cleanup-down.log"
    printf 'cleanup-down\texit=%s\n' "${down_status}" >> "${COMMAND_LOG}"
  fi
  if [[ "${COMPOSE_UP_ATTEMPTED}" -eq 1 || "${PROJECT_BOUNDARY_SAFE}" -eq 1 ]]; then
    verify_project_boundaries && verify_no_project_resources
    boundary_status=$?
  else
    # 边界无法确认时绝不执行 down；仅记录失败并继续生成脱敏证据。
    boundary_status=1
  fi
  if [[ "${down_status}" -ne 0 || "${boundary_status}" -ne 0 ]]; then
    CLEANUP_STATUS="FAIL"
    exit_code=1
  else
    CLEANUP_STATUS="PASS"
  fi

  # cookie 和登录响应只是运行期间的临时材料，无论是否保留证据都不得留存。
  rm -f -- "${EVIDENCE_DIR}"/*.cookie "${EVIDENCE_DIR}"/logs/login*.json

  local leak_count=0 redaction_failed=0 secret value
  local -a sensitive_values=()
  if [[ -n "${E2E_ADMIN_PASSWORD:-}" ]]; then
    sensitive_values+=("${E2E_ADMIN_PASSWORD}")
  fi
  local env_line env_key
  if [[ -f "${ENV_FILE}" ]]; then
    while IFS= read -r env_line || [[ -n "${env_line}" ]]; do
      if [[ "${env_line}" =~ ^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
        env_key="${BASH_REMATCH[1]}"
        value="${BASH_REMATCH[2]}"
        if [[ "${env_key}" =~ (PASSWORD|TOKEN|MASTER_KEY|SECRET|PRIVATE_KEY|API_KEY) ]]; then
          value="${value#\"}"; value="${value%\"}"
          value="${value#\'}"; value="${value%\'}"
          [[ -n "${value}" && "${value}" != 请替换* ]] || continue
          sensitive_values+=("${value}")
        fi
      fi
    done < "${ENV_FILE}"
  fi
  for secret in "${sensitive_values[@]}"; do
    if grep -R -F --binary-files=without-match --quiet -- "${secret}" "${EVIDENCE_DIR}"; then
      leak_count=$((leak_count + 1))
      if ! redact_secret_in_evidence "${secret}"; then
        redaction_failed=1
      fi
    fi
  done
  # 泄漏计数保留在结果中，但最终保留的证据不得再次包含敏感明文。
  for secret in "${sensitive_values[@]}"; do
    if grep -R -F --binary-files=without-match --quiet -- "${secret}" "${EVIDENCE_DIR}"; then
      redaction_failed=1
    fi
  done
  if [[ "${leak_count}" -ne 0 || "${redaction_failed}" -ne 0 ]]; then
    exit_code=1
  fi
  if [[ "${redaction_failed}" -ne 0 ]]; then
    CLEANUP_STATUS="FAIL"
  fi
  local overall="FAIL"
  if [[ "${RUN_FAILED}" -eq 0 && "${CLEANUP_STATUS}" == "PASS" && "${leak_count}" -eq 0 ]]; then
    if ! awk -F '\t' 'NR > 1 && $2 != "PASS" { bad=1 } END { exit(bad ? 1 : 0) }' "${RESULT_TSV}"; then
      overall="FAIL"
    elif ! awk -F '\t' 'NR > 1 && $2 == "BLOCKED" { blocked=1 } END { exit(blocked ? 1 : 0) }' "${RESULT_TSV}"; then
      overall="FAIL"
    else
      overall="PASS"
    fi
  fi
  write_results "${overall}" "${CLEANUP_STATUS}" "${leak_count}"
  write_record "${EVIDENCE_DIR}/results.json"
  exit "${exit_code}"
}
trap 'cleanup "$?"' EXIT

if ! verify_project_boundaries; then
  echo "启动前发现 Compose project 标签越界，拒绝继续。" >&2
  RUN_FAILED=1
  record_result preflight FAIL "project 资源标签校验失败"
else
  PRE_FLIGHT_OK=1
  PROJECT_BOUNDARY_SAFE=1
  record_result preflight PASS "路径、project 名称、环境变量和端口边界已校验"
fi

if [[ "${PRE_FLIGHT_OK}" -eq 1 ]]; then
  if ! record_command compose-config "${COMPOSE[@]}" config --quiet; then
    RUN_FAILED=1
    record_result compose_config FAIL "Compose config --quiet 失败"
  else
    record_result compose_config PASS "Compose config --quiet 通过"
  fi

  if ! record_command pre-clean "${COMPOSE[@]}" down --volumes --remove-orphans; then
    RUN_FAILED=1
    record_result pre_cleanup FAIL "专用 project 启动前清理失败"
  elif ! verify_no_project_resources; then
    RUN_FAILED=1
    record_result pre_cleanup FAIL "启动前专用 project 仍有残留资源"
  else
    record_result pre_cleanup PASS "仅清理并确认专用 project 资源"
  fi
else
  record_result compose_config FAIL "边界校验失败，未执行 Compose config"
  record_result pre_cleanup FAIL "边界校验失败，未执行启动前清理"
fi

if [[ "${RUN_FAILED}" -eq 0 ]]; then
  if ! record_command build-artifacts bash "${ROOT_DIR}/deployment/scripts/build-platform-artifacts.sh"; then
    RUN_FAILED=1
    record_result compose_build FAIL "平台和 Runner 制品构建失败"
  else
    record_result compose_build PASS "使用现有 WSL 制品构建入口构建"
  fi
fi

if [[ "${RUN_FAILED}" -eq 0 ]]; then
  COMPOSE_UP_ATTEMPTED=1
  if ! record_command compose-up "${COMPOSE[@]}" up --detach --build --wait; then
    RUN_FAILED=1
    record_result compose_start FAIL "专用 Compose project 启动失败"
  else
    STARTED=1
    record_result compose_start PASS "专用 Compose project 启动并等待"
  fi
fi

if [[ "${STARTED}" -eq 1 ]]; then
  if wait_healthy "${EVIDENCE_DIR}/compose-ps-after-start.json"; then
    record_result health_after_start PASS "4 个服务均 running/healthy"
  else
    RUN_FAILED=1
    record_result health_after_start FAIL "Compose ps 未达到 4 服务 healthy"
  fi
  if api_login "${SESSION_COOKIE}" "${EVIDENCE_DIR}/logs/login-before-run.json" \
    && wait_runner_online "${SESSION_COOKIE}" "${EVIDENCE_DIR}/runner-status-after-start.json"; then
    record_result runner_heartbeat PASS "管理员认证后 Platform API 返回 ONLINE Runner 心跳"
  else
    RUN_FAILED=1
    record_result runner_heartbeat FAIL "管理员认证失败或未观察到 ONLINE Runner 心跳"
  fi
else
  record_result health_after_start FAIL "Compose 未启动，无法检查服务健康"
  record_result runner_heartbeat FAIL "Compose 未启动，无法检查 Runner 心跳"
fi

F1_META="${EVIDENCE_DIR}/f1-10-run-meta.json"
F2_META="${EVIDENCE_DIR}/f2-10-run-meta.json"
if [[ "${RUN_FAILED}" -eq 0 ]]; then
  if run_browser_spec browser_f1_10 e2e/f1-10-platform-flow.spec.ts "${F1_META}" false; then
    record_result browser_api_case PASS "登录、项目、环境、接口用例、运行和报告查看闭环通过"
  else
    RUN_FAILED=1
    record_result browser_api_case FAIL "f1-10 Playwright 浏览器闭环失败"
  fi
  if run_browser_spec browser_f2_10 e2e/f2-10-suite.spec.ts "${F2_META}" true; then
    record_result browser_suite PASS "场景/集合创建、成员选择和集合报告闭环通过"
  else
    RUN_FAILED=1
    record_result browser_suite FAIL "f2-10 Playwright 集合闭环失败"
  fi
else
  record_result browser_api_case FAIL "前置 Compose/Runner 门禁失败，未执行浏览器闭环"
  record_result browser_suite FAIL "前置 Compose/Runner 门禁失败，未执行浏览器闭环"
fi

if [[ "${RUN_FAILED}" -eq 0 ]]; then
  # 在 restart 紧邻前重新采集基线；启动阶段的状态不能替代这个边界。
  if ! runner_online "${SESSION_COOKIE}" "${EVIDENCE_DIR}/runner-status-before-restart.json"; then
    RUN_FAILED=1
    record_result restart_report_recovery FAIL "重启前无法采集已认证的 Runner baseline"
  elif ! record_command compose-restart "${COMPOSE[@]}" restart postgres platform-api web runner-app; then
    RUN_FAILED=1
    record_result restart_report_recovery FAIL "专用 project 服务重启失败"
  elif ! capture_runner_started_at; then
    RUN_FAILED=1
    record_result restart_report_recovery FAIL "重启后无法确认 runner-app 容器 StartedAt"
  elif ! wait_healthy "${EVIDENCE_DIR}/compose-ps-after-restart.json" \
      || ! api_login "${SESSION_COOKIE}" "${EVIDENCE_DIR}/logs/login-after-restart.json" \
      || ! wait_runner_online_after_baseline "${SESSION_COOKIE}" \
        "${EVIDENCE_DIR}/runner-status-before-restart.json" \
        "${EVIDENCE_DIR}/runner-container-started-at.txt" \
        "${EVIDENCE_DIR}/runner-status-after-restart.json"; then
    RUN_FAILED=1
    record_result restart_report_recovery FAIL "服务重启后未恢复 healthy/重新认证或 Runner 心跳未严格推进"
  else
    if ! check_report "${SESSION_COOKIE}" "${F1_META}" "${EVIDENCE_DIR}/f1-10-report-after-restart.json" false \
      || ! check_report "${SESSION_COOKIE}" "${F2_META}" "${EVIDENCE_DIR}/f2-10-report-after-restart.json" true; then
      RUN_FAILED=1
      record_result restart_report_recovery FAIL "重启后报告或集合成员报告校验失败"
    else
      record_result restart_report_recovery PASS "服务重启后健康、Runner 心跳和两份报告均恢复"
    fi
  fi
else
  record_result restart_report_recovery FAIL "浏览器闭环未全部通过，未执行重启恢复校验"
fi

# 当前 Compose 仅包含 PostgreSQL、Platform API、Web、Runner；以下能力必须显式标记，
# 不能用单接口/集合闭环替代真实证据。
record_result reference_fixtures BLOCKED "pytest-auto-api 十类参考夹具尚未全部映射并在全新 Compose 中逐项执行"
record_result ai_patch BLOCKED "AI Patch 云模型闭环、脱敏上下文和人工确认未纳入当前 Compose 验收"
record_result schedule_cron BLOCKED "Cron 定时任务真实触发尚无验收证据"
record_result cancel_run BLOCKED "运行取消和取消后清理的真实浏览器/Runner 证据未建立"
record_result runner_failure BLOCKED "Runner 失联、重领或异常恢复尚未建立真实 Compose 证据"
record_result minio_attachments BLOCKED "当前 Compose 未部署 MinIO，附件上传/归档/恢复无法验收"
record_result notifications_exports BLOCKED "通知渠道和 HTML/Allure 导出未形成真实 Compose 证据"
record_result postgres_backup_restore BLOCKED "备份恢复脚本尚未绑定本次专用 Compose project，未执行覆盖性恢复"

RUN_FAILED=1
exit 1
