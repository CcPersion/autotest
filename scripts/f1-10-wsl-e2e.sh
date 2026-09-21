#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# F1-10 的全新 WSL 验收入口；运行时状态只属于本次随机 Compose project。
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
OUTPUT_ROOT="${ROOT_DIR}/output/f1-10-e2e"
RUN_TOKEN="$(date -u +%Y%m%d%H%M%S)-$(od -An -N5 -tx1 /dev/urandom | tr -d ' \n')"
PROJECT="autotest-f110-${RUN_TOKEN}"
RUN_DIR="${OUTPUT_ROOT}/验收-${RUN_TOKEN}"
COMPOSE_FILE="${RUN_DIR}/compose.yml"
TARGET_CONFIG="${RUN_DIR}/mock-target.conf"
RUN_ENV="$(mktemp "${TMPDIR:-/tmp}/autotest-f110-env.XXXXXX")"
META_FILE="${RUN_DIR}/run-meta.json"
REPORT_FILE="${RUN_DIR}/F1-10-report.md"
COMMAND_LOG="${RUN_DIR}/commands.log"
SENSITIVE_VALUES=()
COMPOSE_CREATED=false
TEST_ERROR=''
CLEANUP_ERROR=''
MAVEN_PACKAGE_CODE='not-run'
RUNNER_GATE_CODE='not-run'
FIRST_BROWSER_CODE='not-run'
RECOVERY_BROWSER_CODE='not-run'
RESTART_CODE='not-run'
SCAN_CODE='not-run'
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

mkdir -p "${RUN_DIR}"
touch "${COMMAND_LOG}"

log() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*" | tee -a "${COMMAND_LOG}"; }

record_command() {
    local name="$1" output="${RUN_DIR}/$1.log" started ended code
    shift
    started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    log "命令 ${name} 开始: $(printf '%q ' "$@")"
    set +e
    "$@" >"${output}" 2>&1
    code=$?
    set -e
    ended="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '%s\t%s\t%s\t%s\n' "${name}" "${code}" "${started}" "${ended}" >>"${COMMAND_LOG}"
    log "命令 ${name} 退出码=${code}"
    return "${code}"
}

random_port() {
    python3 - <<'PY'
import socket
s = socket.socket()
s.bind(('127.0.0.1', 0))
print(s.getsockname()[1])
s.close()
PY
}

random_value() { od -An -N18 -tx1 /dev/urandom | tr -d ' \n'; }

record_resources() {
    local suffix="$1"
    docker ps -a --filter "label=com.docker.compose.project=${PROJECT}" --format '{{.Names}} {{.Status}}' \
        >"${RUN_DIR}/resources-${suffix}-containers.txt" 2>&1 || true
    docker network ls --filter "label=com.docker.compose.project=${PROJECT}" --format '{{.Name}}' \
        >"${RUN_DIR}/resources-${suffix}-networks.txt" 2>&1 || true
    docker volume ls --filter "label=com.docker.compose.project=${PROJECT}" --format '{{.Name}}' \
        >"${RUN_DIR}/resources-${suffix}-volumes.txt" 2>&1 || true
    pgrep -af "${PROJECT}" >"${RUN_DIR}/resources-${suffix}-processes.txt" 2>&1 || true
}

assert_empty_resources() {
    local suffix="$1" file failed=0
    for file in "${RUN_DIR}/resources-${suffix}-containers.txt" \
                "${RUN_DIR}/resources-${suffix}-networks.txt" \
                "${RUN_DIR}/resources-${suffix}-volumes.txt" \
                "${RUN_DIR}/resources-${suffix}-processes.txt"; do
        if [[ -s "${file}" ]]; then
            log "资源清理失败：${file}"
            failed=1
        fi
    done
    return "${failed}"
}

assert_existing_deployment_unchanged() {
    local suffix="$1"
    docker ps -a --filter label=com.docker.compose.project=deployment --format '{{.ID}} {{.Names}}' \
        >"${RUN_DIR}/deployment-project-${suffix}.txt" 2>&1 || true
    if [[ "${suffix}" == after && -f "${RUN_DIR}/deployment-project-before.txt" ]] \
       && ! cmp -s "${RUN_DIR}/deployment-project-before.txt" "${RUN_DIR}/deployment-project-after.txt"; then
        log '既有 deployment project 状态发生变化。'
        return 1
    fi
}

write_compose_files() {
    local db_password callback_token master_key admin_password
    API_PORT="$(random_port)"
    WEB_PORT="$(random_port)"
    db_password="f110-db-$(random_value)"
    callback_token="f110-callback-$(random_value)"
    # SecretCryptoService accepts exactly 32 decoded bytes (AES-256), not an
    # arbitrary-length base64 string. Derive those bytes without persisting
    # the cleartext key anywhere outside the isolated temporary env file.
    master_key="$(printf '%s' "f110-master-${RUN_TOKEN}" | sha256sum | cut -d' ' -f1 | xxd -r -p | base64 -w0)"
    admin_password="f110-admin-$(random_value)"
    ADMIN_USERNAME='f1-10-admin@local.test'
    ADMIN_PASSWORD="${admin_password}"
    RUNNER_ID="$(cat /proc/sys/kernel/random/uuid)"
    MOCK_SENTINEL='f1-10-sensitive-sentinel'
    SENSITIVE_VALUES+=("${db_password}" "${callback_token}" "${master_key}" "${admin_password}" "${MOCK_SENTINEL}")

    cat >"${RUN_ENV}" <<EOF
AUTOTEST_DB_NAME=autotest
AUTOTEST_DB_USERNAME=autotest
AUTOTEST_DB_PASSWORD=${db_password}
AUTOTEST_ADMIN_USERNAME=${ADMIN_USERNAME}
AUTOTEST_ADMIN_PASSWORD=${admin_password}
AUTOTEST_MASTER_KEY=${master_key}
AUTOTEST_RUNNER_CALLBACK_TOKEN=${callback_token}
AUTOTEST_RUNNER_ID=${RUNNER_ID}
AUTOTEST_API_PORT=${API_PORT}
AUTOTEST_WEB_PORT=${WEB_PORT}
AUTOTEST_SESSION_COOKIE_SECURE=false
EOF
    chmod 600 "${RUN_ENV}"

    cat >"${TARGET_CONFIG}" <<EOF
server {
    listen 8080;
    server_name _;
    default_type application/json;
    location /health {
        add_header X-F1-10-Trace "f1-10-trace" always;
        add_header Set-Cookie "sid=${MOCK_SENTINEL}" always;
        add_header X-Api-Key "${MOCK_SENTINEL}" always;
        return 200 '{"ok":true,"message":"f1-10-mock","token":"${MOCK_SENTINEL}"}';
    }
}
EOF

    cat >"${COMPOSE_FILE}" <<EOF
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: \${AUTOTEST_DB_NAME}
      POSTGRES_USER: \${AUTOTEST_DB_USERNAME}
      POSTGRES_PASSWORD: \${AUTOTEST_DB_PASSWORD}
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready --username=\$\$POSTGRES_USER --dbname=\$\$POSTGRES_DB"]
      interval: 3s
      timeout: 3s
      retries: 30

  mock-target:
    image: nginx:1.27-alpine
    volumes:
      - ${TARGET_CONFIG}:/etc/nginx/conf.d/default.conf:ro

  platform-api:
    build:
      context: ${ROOT_DIR}/platform-api
      dockerfile: Dockerfile
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/\${AUTOTEST_DB_NAME}
      SPRING_DATASOURCE_USERNAME: \${AUTOTEST_DB_USERNAME}
      SPRING_DATASOURCE_PASSWORD: \${AUTOTEST_DB_PASSWORD}
      AUTOTEST_ADMIN_USERNAME: \${AUTOTEST_ADMIN_USERNAME}
      AUTOTEST_ADMIN_PASSWORD: \${AUTOTEST_ADMIN_PASSWORD}
      AUTOTEST_MASTER_KEY: \${AUTOTEST_MASTER_KEY}
      AUTOTEST_RUNNER_CALLBACK_TOKEN: \${AUTOTEST_RUNNER_CALLBACK_TOKEN}
      AUTOTEST_SESSION_COOKIE_SECURE: "false"
      SERVER_PORT: "8080"
    depends_on:
      postgres:
        condition: service_healthy
    ports:
      - "127.0.0.1:\${AUTOTEST_API_PORT}:8080"
    healthcheck:
      test: ["CMD-SHELL", "wget --spider --quiet http://127.0.0.1:8080/actuator/health"]
      interval: 3s
      timeout: 3s
      retries: 40

  runner-app:
    build:
      context: ${ROOT_DIR}/runner-app
      dockerfile: Dockerfile
    environment:
      PLATFORM_DB_URL: jdbc:postgresql://postgres:5432/\${AUTOTEST_DB_NAME}
      PLATFORM_DB_USERNAME: \${AUTOTEST_DB_USERNAME}
      PLATFORM_DB_PASSWORD: \${AUTOTEST_DB_PASSWORD}
      AUTOTEST_PLATFORM_API_URL: http://platform-api:8080
      AUTOTEST_RUNNER_CALLBACK_TOKEN: \${AUTOTEST_RUNNER_CALLBACK_TOKEN}
      AUTOTEST_RUNNER_ID: \${AUTOTEST_RUNNER_ID}
      AUTOTEST_RUNNER_WORK_ROOT: /work/runs
      AUTOTEST_JMETER_VERSION: 5.6.3
      AUTOTEST_RUNNER_POLL_MS: "300"
      JAVA_TOOL_OPTIONS: -Djava.awt.headless=true
    depends_on:
      platform-api:
        condition: service_healthy
    volumes:
      - runner-runs:/work/runs
    command: ["java", "-jar", "/opt/runner/runner.jar"]
    healthcheck:
      test: ["CMD-SHELL", "jmeter --version >/dev/null 2>&1 && test -s /opt/runner/runner.jar"]
      interval: 5s
      timeout: 5s
      retries: 20

  web:
    build:
      context: ${ROOT_DIR}/web
      dockerfile: Dockerfile
    depends_on:
      platform-api:
        condition: service_healthy
    ports:
      - "127.0.0.1:\${AUTOTEST_WEB_PORT}:80"
    healthcheck:
      test: ["CMD-SHELL", "wget --spider --quiet http://127.0.0.1/"]
      interval: 3s
      timeout: 3s
      retries: 30

volumes:
  postgres-data:
  runner-runs:
EOF
    COMPOSE_CREATED=true
}

compose() { docker compose --project-name "${PROJECT}" --env-file "${RUN_ENV}" --file "${COMPOSE_FILE}" "$@"; }

wait_runner_ready() {
    local output="$1"
    python3 - "http://127.0.0.1:${API_PORT}" "${ADMIN_USERNAME}" "${ADMIN_PASSWORD}" "${RUNNER_ID}" >"${output}" <<'PY'
import json
import sys
import time
import urllib.request

base, username, password, runner_id = sys.argv[1:]
deadline = time.time() + 90
last = None
while time.time() < deadline:
    try:
        login = urllib.request.Request(
            base + '/api/v1/auth/login',
            data=json.dumps({'username': username, 'password': password}).encode(),
            headers={'Content-Type': 'application/json'}, method='POST')
        with urllib.request.urlopen(login, timeout=5) as response:
            # The API uses both JSESSIONID and XSRF-TOKEN. Sending only the
            # first Set-Cookie makes the otherwise valid status request look
            # anonymous through the real HTTP stack.
            cookies = [value.split(';', 1)[0] for value in response.headers.get_all('Set-Cookie', [])]
            cookie = '; '.join(cookies)
        status = urllib.request.Request(base + '/api/v1/runners/status', headers={'Cookie': cookie})
        with urllib.request.urlopen(status, timeout=5) as response:
            body = json.load(response)
        print(json.dumps(body, ensure_ascii=False, indent=2))
        match = next((item for item in body if item.get('runnerId') == runner_id), None)
        if match and match.get('status') == 'ONLINE' and match.get('jmeterVersion') == '5.6.3':
            raise SystemExit(0)
        last = body
    except Exception as exc:
        last = str(exc)
    time.sleep(1)
print(json.dumps({'last': last, 'expectedRunnerId': runner_id}, ensure_ascii=False, indent=2))
raise SystemExit(1)
PY
}

scan_and_redact() {
    local scanner="${RUN_DIR}/scan-before-redaction.txt"
    python3 - "${RUN_DIR}" "${SENSITIVE_VALUES[@]}" >"${scanner}" <<'PY'
from pathlib import Path
import zipfile
import sys

root = Path(sys.argv[1])
values = [item for item in sys.argv[2:] if item]
hits = []
for path in root.rglob('*'):
    if not path.is_file() or path.name in {'scan-before-redaction.txt', 'scan-after-redaction.txt'}:
        continue
    relative = str(path.relative_to(root))
    if path.suffix.lower() == '.zip' and zipfile.is_zipfile(path):
        zip_hits = []
        with zipfile.ZipFile(path) as archive:
            for entry in archive.infolist():
                if entry.is_dir():
                    continue
                payload = archive.read(entry)
                if any(value.encode('utf-8') in payload for value in values):
                    zip_hits.append(entry.filename)
        if zip_hits:
            path.unlink()
            hits.append(f'{relative} (zip removed: {", ".join(sorted(zip_hits))})')
        continue
    try:
        payload = path.read_bytes()
    except OSError:
        continue
    changed = False
    for value in values:
        raw = value.encode('utf-8')
        if raw not in payload:
            continue
        if path.suffix.lower() in {'.log', '.json', '.txt', '.md', '.yml', '.yaml', '.conf', '.html', '.trace'}:
            payload = payload.replace(raw, b'***')
            changed = True
        else:
            path.unlink()
            hits.append(f'{relative} (binary removed)')
            payload = None
            break
    if changed:
        path.write_bytes(payload)
        hits.append(relative)
if hits:
    print('\n'.join(sorted(set(hits))))
PY
    log '证据脱敏预处理完成；已记录命中路径并移除不可安全保留的制品。'
}

final_evidence_scan() {
    local scanner="${RUN_DIR}/scan-after-redaction.txt"
    python3 - "${RUN_DIR}" "${SENSITIVE_VALUES[@]}" >"${scanner}" <<'PY'
from pathlib import Path
import sys
import zipfile

root = Path(sys.argv[1])
values = [item.encode('utf-8') for item in sys.argv[2:] if item]
leaks = []
for path in root.rglob('*'):
    if not path.is_file() or path.name in {'scan-before-redaction.txt', 'scan-after-redaction.txt'}:
        continue
    try:
        if path.suffix.lower() == '.zip' and zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as archive:
                for entry in archive.infolist():
                    if not entry.is_dir() and any(value in archive.read(entry) for value in values):
                        leaks.append(f'{path.relative_to(root)}:{entry.filename}')
        elif any(value in path.read_bytes() for value in values):
            leaks.append(str(path.relative_to(root)))
    except (OSError, zipfile.BadZipFile):
        leaks.append(str(path.relative_to(root)))
if leaks:
    print('\n'.join(sorted(set(leaks))))
    raise SystemExit(1)
PY
    if [[ -s "${scanner}" ]]; then
        log '最终证据扫描仍发现敏感值。'
        return 1
    fi
    SCAN_CODE=0
    return 0
}

write_report() {
    local exit_code="$1"
    cat >"${REPORT_FILE}" <<EOF
# F1-10 阶段一端到端验收报告

- 验收时间（UTC）：${STARTED_AT} 至 $(date -u +%Y-%m-%dT%H:%M:%SZ)
- Compose project：${PROJECT}
- 证据目录：${RUN_DIR}
- API/Web 回环端口：${API_PORT:-未生成}/${WEB_PORT:-未生成}
- Runner ID：${RUNNER_ID:-未生成}
- projectId/runId：$(if [[ -f "${META_FILE}" ]]; then tr '\n' ' ' <"${META_FILE}"; else echo '未生成'; fi)
- 总退出码：${exit_code}

## 门禁结果

| 门禁 | 退出码/状态 |
|---|---|
| Runner 全量失败/取消/JMeter 非零与重启相关测试 | ${RUNNER_GATE_CODE} |
| Maven Platform/Runner 构建 | ${MAVEN_PACKAGE_CODE} |
| 首次真实 Chromium 浏览器闭环 | ${FIRST_BROWSER_CODE} |
| Platform/Web/Runner 重启 | ${RESTART_CODE} |
| 重启后重新登录并读取同一 runId 报告 | ${RECOVERY_BROWSER_CODE} |
| 敏感值/sentinel 扫描 | ${SCAN_CODE} |
| 专属容器、网络、卷、进程清理 | $(if [[ -z "${CLEANUP_ERROR}" ]]; then echo PASS; else echo FAIL; fi) |

## 证据索引

- commands.log：每条命令及退出码。
- runner-status-before.json、runner-status-after.json：认证后的 Runner ONLINE/JMeter 5.6.3 状态。
- compose-ps-before-restart.log、compose-ps-after-restart.log：重启前后健康快照。
- run-meta.json：真实 projectId/runId（不含密码、Token 或主密钥）。
- resources-after-cleanup-*.txt：专属资源和派生进程清理枚举。
- scan-before-redaction.txt、scan-after-redaction.txt：脱敏扫描结果。

## 范围声明

本报告只验证 F1-10 的隔离 Compose、真实 API/Web/Runner/JMeter 浏览器闭环和重启报告恢复；不覆盖 F2 场景能力、旧 F4-05 入口或其他运营能力。
EOF
}

cleanup() {
    set +e
    if [[ "${COMPOSE_CREATED}" == true ]]; then
        compose ps >"${RUN_DIR}/compose-ps-before-cleanup.log" 2>&1
        compose logs --no-color >"${RUN_DIR}/compose-logs-before-cleanup.log" 2>&1
        compose down --volumes --remove-orphans >"${RUN_DIR}/compose-down.log" 2>&1
        if [[ $? -ne 0 ]]; then CLEANUP_ERROR='Compose down --volumes --remove-orphans 失败'; fi
    fi
    record_resources after-cleanup
    if ! assert_empty_resources after-cleanup; then CLEANUP_ERROR="${CLEANUP_ERROR:-} 专属资源或进程残留"; fi
    if ! assert_existing_deployment_unchanged after; then CLEANUP_ERROR="${CLEANUP_ERROR:-} 既有 deployment project 被改变"; fi
    # The generated env and mock target contain the intentional sentinel and
    # must never become evidence; remove them before the evidence scan.
    rm -f "${RUN_ENV}" "${TARGET_CONFIG}"
    if ! scan_and_redact; then
        SCAN_CODE=1
        FINAL_EXIT_CODE=1
    fi
    if ! final_evidence_scan; then
        SCAN_CODE=1
        FINAL_EXIT_CODE=1
    fi
    [[ -z "${CLEANUP_ERROR}" ]] || FINAL_EXIT_CODE=1
    # Write the report before the final recursive artifact scan. If the final
    # scan fails, rewrite the report with the failure and scan once more so the
    # last retained report is also covered by the same gate.
    write_report "${FINAL_EXIT_CODE:-1}"
    if ! final_evidence_scan; then
        SCAN_CODE=1
        FINAL_EXIT_CODE=1
        write_report "${FINAL_EXIT_CODE}"
        final_evidence_scan || true
    fi
    set -e
}

trap 'FINAL_EXIT_CODE=${TEST_ERROR:+1}; FINAL_EXIT_CODE=${FINAL_EXIT_CODE:-0}; cleanup' EXIT

if ! assert_existing_deployment_unchanged before; then
    TEST_ERROR='无法建立既有 deployment project 基线。'
    exit 1
fi

write_compose_files
if ! record_command compose-config compose config --quiet; then
    TEST_ERROR='生成 Compose 配置失败。'
    exit 1
fi

if record_command runner-gates mvn -pl runner-app -am clean test; then
    RUNNER_GATE_CODE=0
else
    RUNNER_GATE_CODE=$?
    TEST_ERROR='Runner 失败/取消/JMeter 非零定向门禁失败。'
    exit 1
fi

if record_command maven-package mvn -pl platform-api,runner-app -am -DskipTests package; then
    MAVEN_PACKAGE_CODE=0
else
    MAVEN_PACKAGE_CODE=$?
    TEST_ERROR='Platform/Runner Maven 构建失败。'
    exit 1
fi

if record_command compose-up compose up -d --build --wait --remove-orphans; then
    :
else
    TEST_ERROR='隔离 Compose 启动失败。'
    exit 1
fi

if ! wait_runner_ready "${RUN_DIR}/runner-status-before.json"; then
    TEST_ERROR='Runner 未达到已连接 API/DB 且 ONLINE/JMeter 5.6.3 就绪。'
    exit 1
fi
if ! curl --fail --silent --show-error "http://127.0.0.1:${WEB_PORT}/" >"${RUN_DIR}/web-health.html"; then
    TEST_ERROR='Web 健康检查失败。'
    exit 1
fi
record_resources before-restart

export E2E_BASE_URL="http://127.0.0.1:${WEB_PORT}"
export E2E_ADMIN_USERNAME="${ADMIN_USERNAME}"
export E2E_ADMIN_PASSWORD="${ADMIN_PASSWORD}"
export F1_10_TARGET_BASE_URL='http://mock-target:8080'
export F1_10_TARGET_HOST='mock-target'
export F1_10_RUN_META_FILE="${META_FILE}"
export F1_10_EVIDENCE_DIR="${RUN_DIR}"

if record_command playwright-first npm --prefix "${ROOT_DIR}/web" exec playwright -- \
    test e2e/f1-10-platform-flow.spec.ts --workers=1 --reporter=line --trace=retain-on-failure \
    --output "${RUN_DIR}/playwright-first"; then
    FIRST_BROWSER_CODE=0
else
    FIRST_BROWSER_CODE=$?
    TEST_ERROR='首次真实 Chromium 浏览器闭环失败。'
    exit 1
fi
[[ -s "${META_FILE}" ]] || { TEST_ERROR='首次浏览器未生成 run-meta.json。'; exit 1; }

if record_command compose-ps-before-restart compose ps; then
    :
else
    TEST_ERROR='重启前 Compose 状态采集失败。'
    exit 1
fi
if record_command compose-restart-platform compose restart platform-api; then
    :
else
    RESTART_CODE=$?
    TEST_ERROR='Platform 重启失败。'
    exit 1
fi
if record_command compose-wait-platform compose up -d --wait platform-api; then
    :
else
    RESTART_CODE=$?
    TEST_ERROR='重启后 Platform 未恢复健康。'
    exit 1
fi
if record_command compose-restart-runner compose restart runner-app; then
    :
else
    RESTART_CODE=$?
    TEST_ERROR='Runner 重启失败。'
    exit 1
fi
if record_command compose-wait-runner compose up -d --wait runner-app; then
    :
else
    RESTART_CODE=$?
    TEST_ERROR='重启后 Runner 未恢复健康。'
    exit 1
fi
if record_command compose-restart-web compose restart web; then
    RESTART_CODE=0
else
    RESTART_CODE=$?
    TEST_ERROR='Web 重启失败。'
    exit 1
fi
if record_command compose-wait-web compose up -d --wait web; then
    RESTART_CODE=0
else
    RESTART_CODE=$?
    TEST_ERROR='重启后 Web 未恢复健康。'
    exit 1
fi
if ! wait_runner_ready "${RUN_DIR}/runner-status-after.json"; then
    TEST_ERROR='重启后 Runner 未恢复同一 runnerId 的 ONLINE/JMeter 5.6.3 状态。'
    exit 1
fi
record_command compose-ps-after-restart compose ps

if record_command playwright-recovery npm --prefix "${ROOT_DIR}/web" exec playwright -- \
    test e2e/f1-10-report-recovery.spec.ts --workers=1 --reporter=line --trace=retain-on-failure \
    --output "${RUN_DIR}/playwright-recovery"; then
    RECOVERY_BROWSER_CODE=0
else
    RECOVERY_BROWSER_CODE=$?
    TEST_ERROR='重启后浏览器重新登录/同一 runId 报告恢复失败。'
    exit 1
fi

# Final evidence scanning is performed from cleanup after the temporary
# mock-target fixture and env file have been removed. Scanning here would
# incorrectly treat the live fixture's intentional sentinel as retained
# evidence; cleanup still scans on both success and failure paths.
exit 0
