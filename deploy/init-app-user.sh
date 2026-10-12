#!/bin/sh
set -eu
APP_DB_PASSWORD="$(cat /run/secrets/app_db_password)"
export APP_DB_PASSWORD
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv app_password APP_DB_PASSWORD
SELECT format('CREATE ROLE library_app LOGIN PASSWORD %L', :'app_password') \gexec
ALTER DATABASE library_dev OWNER TO library_app;
ALTER SCHEMA public OWNER TO library_app;
SQL
unset APP_DB_PASSWORD
