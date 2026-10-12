#!/usr/bin/env bash
set -euo pipefail
backup_dir="${1:-$HOME/Library/Application Support/library-system/backups}"
mkdir -p "$backup_dir";chmod 700 "$backup_dir"
export PGHOST="${PGHOST:-127.0.0.1}" PGPORT="${PGPORT:-55432}" PGUSER="${PGUSER:-library_app}"
umask 077
file="$backup_dir/library-$(date +%Y%m%d-%H%M%S).dump"
pg_dump -Fc --no-owner --no-acl "${PGDATABASE:-library_dev}" -f "$file"
echo "备份完成：$file"
