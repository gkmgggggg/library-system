#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/admin-input.sh
trap 'unset ADMIN_USERNAME ADMIN_PASSWORD' EXIT
prepare_admin_cli
read_admin_credentials '需要重置的管理员用户名：'
RESET_ADMIN=true java -jar apps/api/target/library-api-0.0.1-SNAPSHOT.jar --spring.main.web-application-type=none
