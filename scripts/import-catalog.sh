#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
catalog_file="${1:-data/fanqie-catalog.json}"
[[ -f "$catalog_file" ]] || { echo '书目文件不存在，请先运行 npm run catalog:crawl'; exit 1; }
(cd apps/api && ./mvnw -q -DskipTests package)
java -jar apps/api/target/library-api-0.0.1-SNAPSHOT.jar \
  --spring.main.web-application-type=none --library.import-catalog="$catalog_file"
