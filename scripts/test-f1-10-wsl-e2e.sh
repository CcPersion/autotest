#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
MAIN="${ROOT_DIR}/scripts/f1-10-wsl-e2e.sh"
WRAPPER="${ROOT_DIR}/scripts/Test-F1-10E2E.ps1"
RECOVERY="${ROOT_DIR}/web/e2e/f1-10-report-recovery.spec.ts"

fail() { echo "F1-10 合同测试失败：$*" >&2; exit 1; }
[[ -f "${MAIN}" ]] || fail "缺少 WSL Bash 主入口"
[[ -f "${WRAPPER}" ]] || fail "缺少 Windows 薄包装"
[[ -f "${RECOVERY}" ]] || fail "缺少重启后浏览器恢复 spec"

grep -q 'docker compose' "${MAIN}" || fail '主入口未使用 Docker Compose'
grep -q -- '--volumes --remove-orphans' "${MAIN}" || fail '主入口未执行精确 Compose 清理'
grep -q 'F1-10-report.md' "${MAIN}" || fail '主入口未生成中文 F1-10 报告'
grep -q 'runners/status' "${MAIN}" || fail '主入口未检查 Runner 状态'
grep -q 'jmeterVersion' "${MAIN}" || fail '主入口未验证 JMeter 版本'
grep -q 'sentinel' "${MAIN}" || fail '主入口未执行 sentinel 扫描'
grep -q 'platform-api' "${MAIN}" || fail '主入口未编排 Platform API'
grep -q 'runner-app' "${MAIN}" || fail '主入口未编排 Runner'
grep -q 'target' "${MAIN}" || fail '主入口未编排独立 mock target'
grep -Fq '127.0.0.1:\${AUTOTEST_API_PORT}:8080' "${MAIN}" || fail 'API 端口未绑定回环地址'
grep -Fq '127.0.0.1:\${AUTOTEST_WEB_PORT}:80' "${MAIN}" || fail 'Web 端口未绑定回环地址'
grep -q 'restart' "${MAIN}" || fail '主入口未执行重启恢复门禁'
grep -q 'runner-app -am clean test' "${MAIN}" || fail '主入口未纳入 Runner 全量失败/取消/JMeter 门禁'
grep -q -- '--trace=retain-on-failure' "${MAIN}" || fail 'Playwright 不应在成功证据中保留登录 trace'
grep -q 'zipfile' "${MAIN}" || fail '脱敏扫描未检查 ZIP 内部条目'
grep -q 'final_evidence_scan' "${MAIN}" || fail '最终报告后缺少最终证据扫描'
grep -q 'npm\.cmd' "${WRAPPER}" && fail 'Windows 包装不应调用 npm.cmd'
grep -q 'deployment/\.env' "${MAIN}" && fail '主入口不应依赖 deployment/.env'
grep -q 'container_name:' "${MAIN}" && fail '主入口不应使用固定容器名'
grep -q 'runId' "${RECOVERY}" || fail '恢复 spec 未验证 runId'
grep -q 'PASSED' "${RECOVERY}" || fail '恢复 spec 未验证报告终态'

echo 'F1-10 WSL 入口合同测试通过。'
