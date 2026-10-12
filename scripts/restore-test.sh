#!/usr/bin/env bash
set -euo pipefail
backup_file="${1:?需要提供备份文件路径}"
restore_name="${2:-library_restore_$(date +%s)}"
[[ "$restore_name" =~ ^library_restore_[A-Za-z0-9_]+$ ]] || { echo '目标数据库名必须以 library_restore_ 开头';exit 1; }
export PGHOST="${PGHOST:-127.0.0.1}" PGPORT="${PGPORT:-55432}" PGUSER="${PGUSER:-library_owner}"
createdb -O library_app "$restore_name"
pg_restore --exit-on-error --no-owner --no-acl --role=library_app -d "$restore_name" "$backup_file"
psql -d "$restore_name" -v ON_ERROR_STOP=1 -c "SELECT 'books' AS entity,count(*) FROM books UNION ALL SELECT 'copies',count(*) FROM book_copies UNION ALL SELECT 'loans',count(*) FROM loans;"
psql -d "$restore_name" -v ON_ERROR_STOP=1 -c "SELECT bc.code,bc.status,count(l.id) AS active_loans FROM book_copies bc LEFT JOIN loans l ON l.copy_id=bc.id AND l.returned_at IS NULL GROUP BY bc.id HAVING (bc.status='borrowed' AND count(l.id)<>1) OR (bc.status<>'borrowed' AND count(l.id)<>0);"
echo "已恢复到独立数据库 ${restore_name}，请继续核对实际业务数据。"
