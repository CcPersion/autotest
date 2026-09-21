#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

# F2-01 隔离 WSL 验收：只使用本次随机 Compose project、端口和卷。
# Playwright verifies the persisted targetPolicySnapshot in each debug run.
TARGET_POLICY_FIELD='targetPolicySnapshot'
# The gate becomes PASS only after the live DNS/target matrix is validated.
DNS_REBINDING='PENDING'
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
RUN_TOKEN="$(date -u +%Y%m%d%H%M%S)-$(od -An -N5 -tx1 /dev/urandom | tr -d ' \\n')"
PROJECT="autotest-f201-${RUN_TOKEN}"
RUN_DIR="${ROOT_DIR}/output/f2-01-e2e/验收-${RUN_TOKEN}"
COMPOSE_FILE="${RUN_DIR}/compose.yml"
RUN_ENV="$(mktemp "${TMPDIR:-/tmp}/autotest-f201-env.XXXXXX")"
CERT_DIR="${RUN_DIR}/certs"
REPORT_FILE="${RUN_DIR}/F2-01-report.md"
COMMAND_LOG="${RUN_DIR}/commands.log"
META_FILE="${RUN_DIR}/run-meta.json"
COMPOSE_CREATED=false
TEST_ERROR=""
CLEANUP_ERROR=""
MAVEN_CODE=not-run
BROWSER_CODE=not-run
RUNNER_CODE=not-run
SCAN_CODE=not-run
SENSITIVE_VALUES=()
FINAL_CODE=0

mkdir -p "${RUN_DIR}" "${CERT_DIR}"
touch "${COMMAND_LOG}"

log() { printf '[%s] %s\\n' "$(date -u +%H:%M:%S)" "$*" | tee -a "${COMMAND_LOG}"; }
record() {
    local name="$1"; shift
    local out="${RUN_DIR}/${name}.log" code
    local rendered
    rendered="$(printf '%q ' "$@")"
    # Do not persist certificate/password arguments in commands.log.
    rendered="${rendered//pass:p12-pass/pass:<redacted>}"
    log "命令 ${name}: ${rendered}"
    set +e
    "$@" >"${out}" 2>&1
    code=$?
    set -e
    printf '%s\\t%s\\n' "${name}" "${code}" >>"${COMMAND_LOG}"
    log "命令 ${name} 退出码=${code}"
    return "${code}"
}
random_port() { python3 - <<'PY'
import socket
s=socket.socket(); s.bind(('127.0.0.1', 0)); print(s.getsockname()[1]); s.close()
PY
}
# Use a character class so od's wrapped output cannot leak a newline into the
# temporary env file (which would truncate the credential consumed by Compose).
random_value() { od -An -N18 -tx1 /dev/urandom | tr -d '[:space:]'; }

record_resources() {
    local suffix="$1"
    docker ps -a --filter "label=com.docker.compose.project=${PROJECT}" --format '{{.Names}} {{.Status}}' >"${RUN_DIR}/resources-${suffix}-containers.txt" 2>&1 || true
    docker network ls --filter "label=com.docker.compose.project=${PROJECT}" --format '{{.Name}}' >"${RUN_DIR}/resources-${suffix}-networks.txt" 2>&1 || true
    docker volume ls --filter "label=com.docker.compose.project=${PROJECT}" --format '{{.Name}}' >"${RUN_DIR}/resources-${suffix}-volumes.txt" 2>&1 || true
    pgrep -af "${PROJECT}" >"${RUN_DIR}/resources-${suffix}-processes.txt" 2>&1 || true
}
empty_resources() {
    local suffix="$1" file failed=0
    for file in "${RUN_DIR}/resources-${suffix}-containers.txt" "${RUN_DIR}/resources-${suffix}-networks.txt" "${RUN_DIR}/resources-${suffix}-volumes.txt" "${RUN_DIR}/resources-${suffix}-processes.txt"; do
        [[ ! -s "${file}" ]] || failed=1
    done
    return "${failed}"
}
deployment_snapshot() {
    local suffix="$1"
    docker ps -a --filter label=com.docker.compose.project=deployment --format '{{.ID}} {{.Names}} {{.Image}} {{.State}}' >"${RUN_DIR}/deployment-project-${suffix}.txt" 2>&1 || true
}

write_certs() {
    local p12_password='p12-pass'
    record cert-ca openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj /CN=f2-01-ca \
        -keyout "${CERT_DIR}/ca.key" -out "${CERT_DIR}/ca.crt"
    record cert-server-key openssl req -newkey rsa:2048 -nodes -subj /CN=mtls-target \
        -keyout "${CERT_DIR}/server.key" -out "${CERT_DIR}/server.csr"
    printf 'subjectAltName=DNS:mtls-target\\nextendedKeyUsage=serverAuth\\n' >"${CERT_DIR}/server.ext"
    record cert-server openssl x509 -req -days 2 -in "${CERT_DIR}/server.csr" -CA "${CERT_DIR}/ca.crt" -CAkey "${CERT_DIR}/ca.key" \
        -CAcreateserial -out "${CERT_DIR}/server.crt" -extfile "${CERT_DIR}/server.ext"
    record cert-client-key openssl req -newkey rsa:2048 -nodes -subj /CN=f2-01-client \
        -keyout "${CERT_DIR}/client.key" -out "${CERT_DIR}/client.csr"
    printf 'extendedKeyUsage=clientAuth\\n' >"${CERT_DIR}/client.ext"
    record cert-client openssl x509 -req -days 2 -in "${CERT_DIR}/client.csr" -CA "${CERT_DIR}/ca.crt" -CAkey "${CERT_DIR}/ca.key" \
        -CAcreateserial -out "${CERT_DIR}/client.crt" -extfile "${CERT_DIR}/client.ext"
    record cert-p12 openssl pkcs12 -export -out "${CERT_DIR}/client.p12" -inkey "${CERT_DIR}/client.key" \
        -in "${CERT_DIR}/client.crt" -certfile "${CERT_DIR}/ca.crt" -passout "pass:${p12_password}"
    chmod 600 "${CERT_DIR}"/*
    F2_P12_PASSWORD="${p12_password}"
}

write_compose() {
    API_PORT="$(random_port)"; WEB_PORT="$(random_port)"
    DB_PASSWORD="f201-db-$(random_value)"; CALLBACK_TOKEN="f201-callback-$(random_value)"
    ADMIN_PASSWORD="f201-admin-$(random_value)"; RUNNER_ID="$(cat /proc/sys/kernel/random/uuid)"
    MASTER_KEY="$(printf '%s' "f201-master-${RUN_TOKEN}" | sha256sum | cut -d' ' -f1 | xxd -r -p | base64 -w0)"
    MOCK_SENTINEL="f2-01-sentinel-${RUN_TOKEN}"
    ADMIN_USERNAME='f2-01-admin@local.test'
    SENSITIVE_VALUES+=("${DB_PASSWORD}" "${CALLBACK_TOKEN}" "${ADMIN_PASSWORD}" "${MASTER_KEY}" "${MOCK_SENTINEL}" "${F2_P12_PASSWORD}")
    cat >"${RUN_ENV}" <<EOF
AUTOTEST_DB_NAME=autotest
AUTOTEST_DB_USERNAME=autotest
AUTOTEST_DB_PASSWORD=${DB_PASSWORD}
AUTOTEST_ADMIN_USERNAME=${ADMIN_USERNAME}
AUTOTEST_ADMIN_PASSWORD=${ADMIN_PASSWORD}
AUTOTEST_MASTER_KEY=${MASTER_KEY}
AUTOTEST_RUNNER_CALLBACK_TOKEN=${CALLBACK_TOKEN}
AUTOTEST_RUNNER_ID=${RUNNER_ID}
AUTOTEST_API_PORT=${API_PORT}
AUTOTEST_WEB_PORT=${WEB_PORT}
AUTOTEST_SESSION_COOKIE_SECURE=false
EOF
    chmod 600 "${RUN_ENV}"
    cat >"${COMPOSE_FILE}" <<EOF
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: \${AUTOTEST_DB_NAME}
      POSTGRES_USER: \${AUTOTEST_DB_USERNAME}
      POSTGRES_PASSWORD: \${AUTOTEST_DB_PASSWORD}
    volumes: [postgres-data:/var/lib/postgresql/data]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready --username=\$\$POSTGRES_USER --dbname=\$\$POSTGRES_DB"]
      interval: 3s
      timeout: 3s
      retries: 30

  minio:
    image: minio/minio:RELEASE.2024-12-18T13-15-44Z
    command: server /data --console-address :9001
    environment:
      MINIO_ROOT_USER: f201-minio
      MINIO_ROOT_PASSWORD: f201-minio-secret
    volumes: [minio-data:/data]
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 3s
      timeout: 3s
      retries: 30

  echo-target:
    build:
      context: ${ROOT_DIR}/deployment/fixtures
      dockerfile: f2-01-target.Dockerfile
    environment: {F2_TARGET_PORT: "8080"}
    networks:
      default: {}
      f2-dns:
        ipv4_address: 172.31.0.10

  dns-target:
    build:
      context: ${ROOT_DIR}/deployment/fixtures
      dockerfile: f2-01-target.Dockerfile
    environment:
      F2_TARGET_PORT: "8080"
      F2_ACCESS_LOG: /tmp/f2-01-dns-target.log
    volumes: [dns-target-log:/tmp]
    networks:
      f2-dns:
        ipv4_address: 172.31.0.11

  dns-fixture:
    build:
      context: ${ROOT_DIR}/deployment/fixtures
      dockerfile: f2-01-dns.Dockerfile
    environment:
      F2_DNS_PORT: "53"
      F2_DNS_LOG: /tmp/f2-01-dns.log
    volumes: [dns-log:/tmp]
    networks:
      f2-dns:
        ipv4_address: 172.31.0.53

  http-proxy:
    build:
      context: ${ROOT_DIR}/deployment/fixtures
      dockerfile: f2-01-proxy.Dockerfile
    volumes: [proxy-log:/tmp]
    environment:
      F2_PROXY_SIGNING_KEY: \${AUTOTEST_RUNNER_CALLBACK_TOKEN}
      F2_PROXY_ENDPOINTS: http://http-proxy:8081/,http://172.31.0.12:8081/
    depends_on: [echo-target]
    networks:
      default: {}
      f2-dns:
        ipv4_address: 172.31.0.12

  mtls-target:
    build:
      context: ${ROOT_DIR}/deployment/fixtures
      dockerfile: f2-01-target.Dockerfile
    environment:
      F2_TARGET_PORT: "8443"
      F2_TLS_CERT: /certs/server.crt
      F2_TLS_KEY: /certs/server.key
      F2_TLS_CA: /certs/ca.crt
    volumes: ["${CERT_DIR}:/certs:ro"]
    networks:
      default: {}
      f2-dns:
        ipv4_address: 172.31.0.13

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
      AUTOTEST_STORAGE_MINIO_ENDPOINT: http://minio:9000
      AUTOTEST_STORAGE_MINIO_ACCESS_KEY: f201-minio
      AUTOTEST_STORAGE_MINIO_SECRET_KEY: f201-minio-secret
      AUTOTEST_STORAGE_MINIO_BUCKET: f2-01-assets
      SERVER_PORT: "8080"
    depends_on:
      postgres: {condition: service_healthy}
      minio: {condition: service_healthy}
    ports: ["127.0.0.1:\${AUTOTEST_API_PORT}:8080"]
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
      AUTOTEST_DNS_SERVER: 172.31.0.53
      JAVA_TOOL_OPTIONS: -Djava.awt.headless=true
    depends_on: {platform-api: {condition: service_healthy}}
    volumes: [runner-runs:/work/runs]
    dns: [172.31.0.53]
    networks:
      default: {}
      f2-dns:
        ipv4_address: 172.31.0.20
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
    depends_on: {platform-api: {condition: service_healthy}}
    ports: ["127.0.0.1:\${AUTOTEST_WEB_PORT}:80"]
    healthcheck:
      test: ["CMD-SHELL", "wget --spider --quiet http://127.0.0.1/"]
      interval: 3s
      timeout: 3s
      retries: 30

volumes:
  postgres-data:
  minio-data:
  runner-runs:
  proxy-log:
  dns-target-log:
  dns-log:

networks:
  f2-dns:
    ipam:
      config:
        - subnet: 172.31.0.0/24
EOF
    COMPOSE_CREATED=true
}

compose() { docker compose --project-name "${PROJECT}" --env-file "${RUN_ENV}" --file "${COMPOSE_FILE}" "$@"; }

wait_runner() {
    python3 - "http://127.0.0.1:${API_PORT}" "${ADMIN_USERNAME}" "${ADMIN_PASSWORD}" "${RUNNER_ID}" >"${RUN_DIR}/runner-status-before.json" <<'PY'
import json, sys, time, urllib.request
base, username, password, runner_id = sys.argv[1:]
deadline = time.time() + 150
last = None
while time.time() < deadline:
    try:
        login = urllib.request.Request(base + '/api/v1/auth/login', data=json.dumps({'username': username, 'password': password}).encode(), headers={'Content-Type':'application/json'}, method='POST')
        with urllib.request.urlopen(login, timeout=5) as response:
            cookies = [v.split(';',1)[0] for v in response.headers.get_all('Set-Cookie', [])]
        request = urllib.request.Request(base + '/api/v1/runners/status', headers={'Cookie': '; '.join(cookies)})
        with urllib.request.urlopen(request, timeout=5) as response: last = json.load(response)
        match = next((item for item in last if item.get('runnerId') == runner_id), None)
        if match and match.get('status') == 'ONLINE' and match.get('jmeterVersion') == '5.6.3': raise SystemExit(0)
    except Exception as exc: last = str(exc)
    time.sleep(1)
print(json.dumps({'last': last, 'runnerId': runner_id}, ensure_ascii=False, indent=2)); raise SystemExit(1)
PY
}

scan() {
    local output="$1"; shift
    python3 - "${RUN_DIR}" "$@" >"${output}" <<'PY'
from pathlib import Path
import sys, zipfile
root=Path(sys.argv[1]); values=[v.encode() for v in sys.argv[2:] if v]; leaks=[]
for path in root.rglob('*'):
    if not path.is_file() or path.name in {'scan-before-redaction.txt','scan-after-redaction.txt'}: continue
    try:
        if path.suffix.lower()=='.zip' and zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as archive:
                if any(any(v in archive.read(item) for v in values) for item in archive.infolist() if not item.is_dir()): leaks.append(str(path.relative_to(root)))
        elif any(v in path.read_bytes() for v in values): leaks.append(str(path.relative_to(root)))
    except Exception: leaks.append(str(path.relative_to(root)))
print('\\n'.join(sorted(set(leaks))))
raise SystemExit(1 if leaks else 0)
PY
}

write_report() {
    local code="$1"
    local cleanup_status='PASS'
    [[ -z "${CLEANUP_ERROR}" ]] || cleanup_status='FAIL'
    cat >"${REPORT_FILE}" <<EOF
# F2-01 完整 HTTP 输入隔离验收报告

- Compose project：${PROJECT}
- 证据目录：${RUN_DIR}
- 隔离 API/Web 端口：${API_PORT}/${WEB_PORT}
- 总退出码：${code}

## 门禁

| 门禁 | 结果 |
|---|---|
| Runner 全量 Maven 测试 | ${RUNNER_CODE} |
| Platform/Runner 构建 | ${MAVEN_CODE} |
| Chromium 上传/选择/预览/发送/报告 | ${BROWSER_CODE} |
| MinIO、proxy、mTLS、echo Compose | 由 compose-ps/logs 记录 |
| targetPolicySnapshot | Playwright 运行计划断言 |
| DNS_REBINDING 零触达 | ${DNS_REBINDING} |
| 敏感值与 ZIP 扫描 | ${SCAN_CODE} |
| 专属资源精确清理 | ${cleanup_status} |

## 证据

- commands.log、compose.yml、runner-status-before.json、run-meta.json、run-matrix.json
- proxy.log：真实 HTTP proxy 触达记录
- resources-after-cleanup-*：专属容器、网络、卷、进程清理统计
- scan-before-redaction.txt / scan-after-redaction.txt

## 未完成边界

本轮真实 Compose 已覆盖 PostgreSQL、MinIO、Platform、Web、Runner、固定 JMeter 5.6.3、echo、HTTP proxy、mTLS，以及独立 DNS/重绑定目标。只有 run-matrix.json 与 dns.log、dns-target.log 同时满足 CIDR/端口/混合地址/重绑定零触达断言时，DNS_REBINDING 才记为 PASS。
EOF
}

redact_evidence() {
    # Never retain raw login/request secrets in evidence. Failed Playwright
    # traces are removed because replacing bytes inside a zip is not reliable.
    find "${RUN_DIR}" -type f -name '*.zip' -delete 2>/dev/null || true
    python3 - "${RUN_DIR}" "${SENSITIVE_VALUES[@]}" <<'PY'
from pathlib import Path
import sys
root=Path(sys.argv[1]); values=[v.encode() for v in sys.argv[2:] if v]
for path in root.rglob('*'):
    if not path.is_file() or path.name.startswith('scan-'):
        continue
    try:
        data=path.read_bytes()
        for value in values:
            data=data.replace(value, b'<redacted>')
        path.write_bytes(data)
    except OSError:
        pass
PY
}

cleanup() {
    set +e
    # EXIT traps run after both the happy path and an explicit failure. Keep
    # the report's aggregate code derived from the recorded test error rather
    # than relying on the status of the final scan/report command.
    [[ -z "${TEST_ERROR}" ]] || FINAL_CODE=1
    if [[ "${COMPOSE_CREATED}" == true ]]; then
        compose ps >"${RUN_DIR}/compose-ps-before-cleanup.log" 2>&1
        compose logs --no-color >"${RUN_DIR}/compose-logs-before-cleanup.log" 2>&1
        compose cp http-proxy:/tmp/f2-01-proxy.log "${RUN_DIR}/proxy.log" >"${RUN_DIR}/proxy-copy.log" 2>&1 || true
        compose cp dns-fixture:/tmp/f2-01-dns.log "${RUN_DIR}/dns.log" >"${RUN_DIR}/dns-copy.log" 2>&1 || true
        compose cp dns-target:/tmp/f2-01-dns-target.log "${RUN_DIR}/dns-target.log" >"${RUN_DIR}/dns-target-copy.log" 2>&1 || true
        # Preserve only controlled Runner runtime diagnostics before its
        # private volume is removed; JMeter plans and raw response bodies are
        # intentionally excluded from evidence.
        compose exec -T runner-app sh -c 'find /work/runs -type f -name jmeter.log -exec sh -c '\''echo "--- $1"; cat "$1"'\'' _ {} \;' \
            >"${RUN_DIR}/runner-runtime.log" 2>&1 || true
        compose exec -T runner-app sh -c 'find /work/runs -maxdepth 3 -type f -printf "%p %s bytes\\n"' \
            >"${RUN_DIR}/runner-runs-list.txt" 2>&1 || true
        # Keep only safe JTL attributes for diagnosing a failed real sample;
        # never persist response bodies, URLs, or raw failure messages.
        compose exec -T runner-app sh -c 'for f in $(find /work/runs -type f -name result.jtl); do echo "--- $f"; grep -o "<httpSample[^>]*" "$f" | sed -E "s/ url=\"[^\"]*\"/ url=\"REDACTED\"/g; s/ rm=\"[^\"]*\"/ rm=\"REDACTED\"/g"; done' \
            >"${RUN_DIR}/runner-jtl-summary.log" 2>&1 || true
        compose down --volumes --remove-orphans >"${RUN_DIR}/compose-down.log" 2>&1 || CLEANUP_ERROR='compose down failed'
    fi
    record_resources after-cleanup
    empty_resources after-cleanup || CLEANUP_ERROR="${CLEANUP_ERROR:-resource residue}"
    deployment_snapshot after
    rm -f "${RUN_ENV}" "${CERT_DIR}"/*
    redact_evidence
    scan "${RUN_DIR}/scan-before-redaction.txt" "${SENSITIVE_VALUES[@]}" || true
    scan "${RUN_DIR}/scan-after-redaction.txt" "${SENSITIVE_VALUES[@]}"; [[ $? -eq 0 ]] || SCAN_CODE=1
    [[ -z "${CLEANUP_ERROR}" ]] || FINAL_CODE=1
    [[ "${SCAN_CODE}" == not-run ]] && SCAN_CODE=0
    write_report "${FINAL_CODE:-1}"
    scan "${RUN_DIR}/scan-after-report.txt" "${SENSITIVE_VALUES[@]}" || { SCAN_CODE=1; FINAL_CODE=1; write_report "${FINAL_CODE}"; }
}
trap cleanup EXIT

deployment_snapshot before
write_certs
write_compose
if ! record compose-config compose config --quiet; then TEST_ERROR='compose config failed'; exit 1; fi
if record runner-gates mvn -pl runner-app -am clean test; then RUNNER_CODE=0; else RUNNER_CODE=$?; TEST_ERROR='runner gates failed'; exit 1; fi
if record maven-package mvn -pl platform-api,runner-app -am -DskipTests package; then MAVEN_CODE=0; else MAVEN_CODE=$?; TEST_ERROR='maven package failed'; exit 1; fi
if record compose-up compose up -d --build --wait --remove-orphans; then :; else TEST_ERROR='compose startup failed'; exit 1; fi
if ! wait_runner; then TEST_ERROR='runner did not become ONLINE with JMeter 5.6.3'; exit 1; fi
if ! curl --fail --silent --show-error "http://127.0.0.1:${WEB_PORT}/" >"${RUN_DIR}/web-health.html"; then TEST_ERROR='web health failed'; exit 1; fi

export E2E_BASE_URL="http://127.0.0.1:${WEB_PORT}"
export E2E_ADMIN_USERNAME="${ADMIN_USERNAME}"
export E2E_ADMIN_PASSWORD="${ADMIN_PASSWORD}"
export F2_01_TARGET_BASE_URL='http://echo-target:8080'
export F2_01_TARGET_HOST='echo-target'
export F2_01_TARGET_IP='172.31.0.10'
export F2_01_CIDR_IP='172.31.0.11'
export F2_01_PROXY_IP='172.31.0.12'
export F2_01_REBIND_HOST='rebind-target'
export F2_01_MIXED_HOST='mixed-target'
export F2_01_P12_FILE="${CERT_DIR}/client.p12"
export F2_01_P12_PASSWORD="${F2_P12_PASSWORD}"
export F2_01_EVIDENCE_DIR="${RUN_DIR}"
export F2_01_RUN_META_FILE="${META_FILE}"
if record playwright npm --prefix "${ROOT_DIR}/web" exec playwright -- test e2e/f2-01-http-input.spec.ts --workers=1 --reporter=line --trace=retain-on-failure --output "${RUN_DIR}/playwright"; then BROWSER_CODE=0; else BROWSER_CODE=$?; TEST_ERROR='real browser F2-01 flow failed'; exit 1; fi
if ! compose cp http-proxy:/tmp/f2-01-proxy.log "${RUN_DIR}/proxy-live.log" >"${RUN_DIR}/proxy-live-copy.log" 2>&1; then
    TEST_ERROR='proxy access log unavailable'; exit 1
fi
if ! grep -q '^GET ' "${RUN_DIR}/proxy-live.log" 2>/dev/null && ! grep -q 'http://' "${RUN_DIR}/proxy-live.log" 2>/dev/null; then
    TEST_ERROR='proxy had no recorded request'; exit 1
fi
# Compose log collection is supplementary; a short-lived fixture may have no
# stdout stream even though its access log was copied successfully.
compose logs --no-color http-proxy >"${RUN_DIR}/proxy-compose.log" 2>&1 || true
if ! compose cp dns-fixture:/tmp/f2-01-dns.log "${RUN_DIR}/dns-live.log" >"${RUN_DIR}/dns-live-copy.log" 2>&1; then
    TEST_ERROR='DNS fixture log unavailable'; exit 1
fi
if ! compose cp dns-target:/tmp/f2-01-dns-target.log "${RUN_DIR}/dns-target-live.log" >"${RUN_DIR}/dns-target-live-copy.log" 2>&1; then
    TEST_ERROR='DNS target log unavailable'; exit 1
fi
if ! python3 - "${RUN_DIR}/run-matrix.json" "${RUN_DIR}/dns-live.log" "${RUN_DIR}/dns-target-live.log" <<'PY'
import json
import sys
from pathlib import Path

matrix = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
dns_log = Path(sys.argv[2]).read_text(encoding="utf-8")
target_log = Path(sys.argv[3]).read_text(encoding="utf-8")
assert matrix["direct"]["ipCidr"]["status"] == "PASSED"
assert matrix["ports"]["defaultPort"]["status"] == "FAILED"
assert matrix["ports"]["unauthorizedExplicitPort"]["status"] == "FAILED"
assert matrix["mixedAaaaRejected"]["status"] == "FAILED"
assert any(step.get("responseCode") == "TARGET_DNS_NOT_ALLOWED" or step.get("responseMessage") == "TARGET_DNS_NOT_ALLOWED" for step in matrix["mixedAaaaRejected"].get("steps", []))
assert matrix["dnsRebinding"]["run"]["status"] == "FAILED"
assert any(step.get("responseCode") == "TARGET_DNS_NOT_ALLOWED" or step.get("responseMessage") == "TARGET_DNS_NOT_ALLOWED" for step in matrix["dnsRebinding"]["run"].get("steps", []))
assert matrix["proxy"]["hostname"] == "PASSED" and matrix["proxy"]["ipCidr"] == "PASSED"
assert "name=mixed-target type=28 answers=ffff::1" in dns_log
assert "name=rebind-target" in dns_log and "answers=127.0.0.1" in dns_log
assert "host=mixed-target" not in target_log
assert "host=172.31.0.10" not in target_log
assert "host=rebind-target path=/redirect-3" not in target_log
PY
then
    TEST_ERROR='DNS/CIDR/port/rebinding matrix evidence failed'; exit 1
fi
DNS_REBINDING='PASS'
if [[ "${DNS_REBINDING}" != 'PASS' ]]; then
    TEST_ERROR='DNS rebinding/CIDR zero-touch gate is not implemented'
    exit 1
fi
exit 0
