#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v initdb >/dev/null || { echo '测试需要 PostgreSQL 16 的 initdb/pg_ctl/psql'; exit 1; }
test_dir="$(mktemp -d "${TMPDIR:-/tmp}/library-test.XXXXXX")"
test_port="$(python3 - <<'PY'
import socket
s=socket.socket();s.bind(('127.0.0.1',0));print(s.getsockname()[1]);s.close()
PY
)"
cleanup() { pg_ctl -D "$test_dir/data" -m immediate stop >/dev/null 2>&1 || true; rm -rf "$test_dir"; }
trap cleanup EXIT
initdb -D "$test_dir/data" -U library_test --auth=trust --encoding=UTF8 --no-locale >/dev/null
pg_ctl -D "$test_dir/data" -l "$test_dir/postgres.log" -o "-h 127.0.0.1 -p $test_port -k '$test_dir'" start >/dev/null
createdb -h 127.0.0.1 -p "$test_port" -U library_test library_test
export TEST_DATABASE_URL="jdbc:postgresql://127.0.0.1:$test_port/library_test" TEST_DATABASE_USER=library_test TEST_DATABASE_PASSWORD=''
npm run typecheck
(cd apps/api && ./mvnw test)
