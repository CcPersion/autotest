#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
IMAGE="${RUNNER_IMAGE:-autotest/runner:0.1.0}"

mvn -pl runner-app -am package -DskipTests

docker build \
  --file "${REPO_ROOT}/runner-app/Dockerfile" \
  --tag "${IMAGE}" \
  "${REPO_ROOT}/runner-app"
