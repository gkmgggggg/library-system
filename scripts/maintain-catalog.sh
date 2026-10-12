#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:-}" in
  prepare) arguments=(--library.prepare-fanqie-samples=true) ;;
  distribute) arguments=(--library.distribute-fanqie-copies=true) ;;
  covers) arguments=(--library.import-covers="${2:-data/fanqie-covers.json}") ;;
  *) echo '用法：maintain-catalog.sh prepare|distribute|covers [封面清单]'; exit 1 ;;
esac
(cd apps/api && ./mvnw -q -DskipTests package)
java -jar apps/api/target/library-api-0.0.1-SNAPSHOT.jar --spring.main.web-application-type=none "${arguments[@]}"
