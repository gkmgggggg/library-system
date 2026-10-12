#!/bin/sh
set -eu
if [ -f /run/secrets/app_db_password ]; then DATABASE_PASSWORD="$(cat /run/secrets/app_db_password)"; export DATABASE_PASSWORD; fi
exec java ${JAVA_OPTS:--Xms64m -Xmx256m -XX:MaxMetaspaceSize=160m} -jar /app/app.jar "$@"
