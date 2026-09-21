#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"

cd "${REPO_ROOT}"
mvn -pl platform-api -am package -DskipTests
mvn -pl runner-app -am package -DskipTests

# Web 依赖和静态资源在 web/Dockerfile 的 Linux 构建阶段生成，避免
# Windows 与 WSL 共用 node_modules 时混入不同平台的可选二进制。
echo "Platform API 与 Runner 构建完成；Web 静态资源由 Docker 镜像阶段构建。"
