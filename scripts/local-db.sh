#!/usr/bin/env bash
set -euo pipefail
data_dir="${LIBRARY_PG_DATA:-$HOME/Library/Application Support/library-system/postgres}"
socket_dir="${LIBRARY_PG_SOCKET:-$HOME/Library/Application Support/library-system/socket}"
port="${LIBRARY_PG_PORT:-55432}"
action="${1:-start}"
command -v pg_ctl >/dev/null || { echo '需要安装 PostgreSQL 16，并将 bin 加入 PATH'; exit 1; }
if [[ "$action" == stop ]]; then
  if [[ -f "$data_dir/PG_VERSION" ]]; then pg_ctl -D "$data_dir" -m fast stop; fi
  exit 0
fi
mkdir -p "$data_dir" "$socket_dir"
chmod 700 "$data_dir" "$socket_dir"
if [[ ! -f "$data_dir/PG_VERSION" ]]; then
  initdb -D "$data_dir" -U library_owner --auth-local=trust --auth-host=trust --encoding=UTF8 --no-locale >/dev/null
fi
if ! pg_ctl -D "$data_dir" status >/dev/null 2>&1; then
  pg_ctl -D "$data_dir" -l "$data_dir/server.log" -o "-h 127.0.0.1 -p $port -k '$socket_dir'" start
fi
export PGHOST="$socket_dir" PGPORT="$port" PGUSER=library_owner
if [[ "$(psql -d postgres -Atc "SELECT count(*) FROM pg_roles WHERE rolname='library_app'")" == 0 ]]; then
  psql -d postgres -v ON_ERROR_STOP=1 -c 'CREATE ROLE library_app LOGIN' >/dev/null
fi
if [[ "$(psql -d postgres -Atc "SELECT count(*) FROM pg_database WHERE datname='library_dev'")" == 0 ]]; then
  createdb -O library_app library_dev
fi
echo "本地独立数据库已就绪：127.0.0.1:$port/library_dev"
echo '仅限本机学习：此专用实例使用 trust，不得改为监听公网。数据位于工作区外。'
