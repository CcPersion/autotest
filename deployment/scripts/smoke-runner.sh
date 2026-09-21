#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
IMAGE="${RUNNER_IMAGE:-autotest/runner:0.1.0}"
RUN_DIR="$(mktemp -d)"
trap 'rm -rf "${RUN_DIR}"' EXIT
chmod 777 "${RUN_DIR}"

docker run --rm --entrypoint /bin/sh "${IMAGE}" -c \
  'test "$(id -u)" -ne 0 && java -version 2>&1 | grep -q "17\."'
docker run --rm "${IMAGE}" jmeter --version

# 临时结果目录需要允许镜像内的非 root 用户写入。
docker run --rm \
  --volume "${RUN_DIR}:/work/runs" \
  "${IMAGE}" jmeter -n -t /opt/runner/smoke.jmx -l /work/runs/result.jtl

test -s "${RUN_DIR}/result.jtl"
echo "Smoke result: ${RUN_DIR}/result.jtl"
