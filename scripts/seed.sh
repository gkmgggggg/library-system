#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f apps/api/target/library-api-0.0.1-SNAPSHOT.jar ]]; then (cd apps/api && ./mvnw -q -DskipTests package); fi
SEED_DEMO=true java -jar apps/api/target/library-api-0.0.1-SNAPSHOT.jar --spring.main.web-application-type=none
