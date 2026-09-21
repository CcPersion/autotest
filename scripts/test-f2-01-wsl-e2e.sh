#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
MAIN="${ROOT_DIR}/scripts/f2-01-wsl-e2e.sh"
SPEC="${ROOT_DIR}/web/e2e/f2-01-http-input.spec.ts"

[[ -f "${MAIN}" ]] || { echo 'F2-01 主入口缺失' >&2; exit 1; }
[[ -f "${SPEC}" ]] || { echo 'F2-01 Playwright spec 缺失' >&2; exit 1; }
grep -q 'docker compose' "${MAIN}" || { echo '未使用隔离 Compose' >&2; exit 1; }
grep -q -- '--volumes --remove-orphans' "${MAIN}" || { echo '未精确清理卷和孤儿' >&2; exit 1; }
grep -q 'minio' "${MAIN}" || { echo '未编排 MinIO' >&2; exit 1; }
grep -q 'http-proxy' "${MAIN}" || { echo '未编排真实 HTTP proxy' >&2; exit 1; }
grep -q 'mtls-target' "${MAIN}" || { echo '未编排 mTLS target' >&2; exit 1; }
grep -q 'targetPolicySnapshot' "${MAIN}" || { echo '未验证 targetPolicySnapshot' >&2; exit 1; }
grep -q 'DNS_REBINDING' "${MAIN}" || { echo '未验证 DNS rebinding 零触达' >&2; exit 1; }
grep -q 'F2-01-report.md' "${MAIN}" || { echo '未生成 F2-01 报告' >&2; exit 1; }
grep -q 'f2-01-http-input.spec.ts' "${MAIN}" || { echo '未执行真实浏览器 spec' >&2; exit 1; }
grep -q 'F2_01_TARGET_BASE_URL' "${SPEC}" || { echo 'spec 未使用独立 target' >&2; exit 1; }
grep -q 'multipart' "${SPEC}" || { echo 'spec 未覆盖 multipart' >&2; exit 1; }
grep -q 'URLENCODED' "${SPEC}" || { echo 'spec 未覆盖 URLENCODED' >&2; exit 1; }
echo 'F2-01 WSL 入口合同测试通过。'
