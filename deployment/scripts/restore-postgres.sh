#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

if [[ "${1:-}" != "--confirm" || -z "${2:-}" ]]; then
  echo "恢复会覆盖当前 PostgreSQL 数据；用法：restore-postgres.sh --confirm <备份文件>" >&2
  exit 2
fi
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ROOT_DIR}/deployment/.env"
INPUT_FILE="$2"
if [[ ! -f "$ENV_FILE" || ! -f "$INPUT_FILE" ]]; then
  echo "deployment/.env 或备份文件不存在" >&2
  exit 2
fi
# shellcheck disable=SC1090
set -a
source "$ENV_FILE"
set +a
: "${AUTOTEST_DB_NAME:?AUTOTEST_DB_NAME 未配置}"
: "${AUTOTEST_DB_USERNAME:?AUTOTEST_DB_USERNAME 未配置}"
: "${AUTOTEST_DB_PASSWORD:?AUTOTEST_DB_PASSWORD 未配置}"

docker compose --env-file "$ENV_FILE" -f "$ROOT_DIR/deployment/docker-compose.yml" \
  exec -T -e "PGPASSWORD=${AUTOTEST_DB_PASSWORD}" postgres \
  pg_restore --clean --if-exists --no-owner --no-privileges --username "$AUTOTEST_DB_USERNAME" \
  --dbname "$AUTOTEST_DB_NAME" < "$INPUT_FILE"
echo "PostgreSQL 恢复完成；请按运维说明执行健康检查和报告抽查。"
