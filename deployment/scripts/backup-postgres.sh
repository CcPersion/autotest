#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ROOT_DIR}/deployment/.env"
OUTPUT_DIR="${1:-${ROOT_DIR}/backups}"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "未找到 deployment/.env；请先复制 .env.example 并在本机填写配置" >&2
  exit 2
fi

# shellcheck disable=SC1090
set -a
source "$ENV_FILE"
set +a
: "${AUTOTEST_DB_NAME:?AUTOTEST_DB_NAME 未配置}"
: "${AUTOTEST_DB_USERNAME:?AUTOTEST_DB_USERNAME 未配置}"
: "${AUTOTEST_DB_PASSWORD:?AUTOTEST_DB_PASSWORD 未配置}"
mkdir -p -- "$OUTPUT_DIR"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUTPUT_FILE="${OUTPUT_DIR%/}/autotest-${STAMP}.dump"

docker compose --env-file "$ENV_FILE" -f "$ROOT_DIR/deployment/docker-compose.yml" \
  exec -T -e "PGPASSWORD=${AUTOTEST_DB_PASSWORD}" postgres \
  pg_dump --format=custom --no-owner --no-privileges --username "$AUTOTEST_DB_USERNAME" "$AUTOTEST_DB_NAME" \
  > "$OUTPUT_FILE"
chmod 600 "$OUTPUT_FILE"
echo "PostgreSQL 备份已写入：$OUTPUT_FILE"
