#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/admin-input.sh
trap 'unset ADMIN_PASSWORD ADMIN_USERNAME' EXIT
prepare_admin_cli
read_admin_credentials '管理员用户名（字母/数字/下划线，3～32 位）：'
INITIALIZE_ADMIN=true java -jar apps/api/target/library-api-0.0.1-SNAPSHOT.jar --spring.main.web-application-type=none
