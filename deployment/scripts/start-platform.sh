#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${REPO_ROOT}/deployment/docker-compose.yml"
ENV_FILE="${REPO_ROOT}/deployment/.env"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "缺少 deployment/.env；请先复制 deployment/.env.example 并填写本机值。" >&2
  exit 2
fi

"${SCRIPT_DIR}/build-platform-artifacts.sh"
docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" up --detach --build --wait
