#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p data
target_file=data/fanqie-cover-targets.json
psql -w -h "${PGHOST:-127.0.0.1}" -p "${PGPORT:-55432}" -U "${PGUSER:-library_app}" -d "${PGDATABASE:-library_dev}" \
  -Atc "SELECT coalesce(json_agg(external_id ORDER BY external_id),'[]'::json) FROM book_sources WHERE source='fanqie'" > "$target_file.tmp"
mv "$target_file.tmp" "$target_file"
python3 scripts/crawl-fanqie-covers.py --targets "$target_file" "$@"
