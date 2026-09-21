#!/usr/bin/env bash
set -Eeuo pipefail

# 只探测真实 WSL Docker CLI 的 network 参数解析，不修改 Docker 资源。
set +e
output="$(wsl.exe docker network ls -q 2>&1)"
status=$?
set -e
if [[ "${status}" -ne 0 ]]; then
  echo "WSL Docker network ls -q 探针失败。" >&2
  exit 1
fi
if [[ "${output}" == *"unknown shorthand flag"* || "${output}" == *"unknown flag"* ]]; then
  echo "WSL Docker network ls -q 仍被 Docker CLI 拒绝。" >&2
  exit 1
fi
echo "F4-05 WSL Docker network ls -q 探针通过。"
