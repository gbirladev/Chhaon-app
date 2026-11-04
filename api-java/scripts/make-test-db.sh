#!/usr/bin/env bash
# Creates (or re-creates) the throwaway database used by the Java tests: chhaon_java_test.
#
# It lives inside the LOCAL Supabase Postgres (container supabase_db_carelink) and is built from a
# SCHEMA-ONLY dump (pg_dump -s) of the local `postgres` database, so the app tables, the SQL functions
# (generate_dose_events, confirm_dose_slot, consume_stock, ...), the auth/storage schemas and the roles
# all exist, with NO data from the shared development database.
#
# Safety:
#   * the shared `postgres` database is only READ (pg_dump); nothing is ever written to it;
#   * the script refuses to touch any database other than chhaon_java_test;
#   * it is idempotent: run it again to get a fresh, empty copy (the old test database is dropped first).
#
# Usage:   api-java/scripts/make-test-db.sh            (from anywhere)
# Env:     SUPABASE_DB_CONTAINER (default supabase_db_carelink), SOURCE_DB (default postgres)
#
# After the schema is loaded the Java tests apply the Flyway migration V1__custom_auth.sql themselves
# (Spring runs Flyway on start), so profiles are no longer tied to auth.users.
set -euo pipefail

CONTAINER="${SUPABASE_DB_CONTAINER:-supabase_db_carelink}"
SOURCE_DB="${SOURCE_DB:-postgres}"
TEST_DB="chhaon_java_test"   # fixed on purpose: never parameterise the database that gets dropped

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "Container $CONTAINER is not running. Start local Supabase first: npx supabase start" >&2
  exit 1
fi

psql_admin() { docker exec -i "$CONTAINER" psql -U postgres -d "$SOURCE_DB" -v ON_ERROR_STOP=1 -q "$@"; }

echo "Dropping and re-creating $TEST_DB ..."
psql_admin -c "drop database if exists ${TEST_DB} with (force)"
psql_admin -c "create database ${TEST_DB}"

echo "Loading the schema-only dump of ${SOURCE_DB} into ${TEST_DB} ..."
# Some objects of the Supabase image (extensions, publications, ownership) may print harmless errors;
# the dump is loaded without ON_ERROR_STOP and the result is verified below.
LOG="$(mktemp)"
docker exec "$CONTAINER" pg_dump -U postgres -d "$SOURCE_DB" -s \
  | docker exec -i "$CONTAINER" psql -U postgres -d "$TEST_DB" -v ON_ERROR_STOP=0 -q >/dev/null 2>"$LOG" || true
echo "psql reported $(grep -c ERROR "$LOG" || true) error line(s) while loading (see $LOG)."

# The ONLY rows copied: the pricing-plans catalogue (reference data, no personal data). profiles.plan has a
# foreign key to it, so no test caregiver could be created without it.
echo "Copying the plans catalogue (reference data) ..."
docker exec "$CONTAINER" pg_dump -U postgres -d "$SOURCE_DB" -a -t public.plans \
  | docker exec -i "$CONTAINER" psql -U postgres -d "$TEST_DB" -v ON_ERROR_STOP=1 -q >/dev/null

# Migrations NEWER than what the local dev database may have (this branch can add migrations that were never
# applied to the shared database, which this script never writes to). Each file is applied in one transaction;
# if it fails because the dump already contains it ("already exists"), it is rolled back and skipped.
AFTER="${APPLY_MIGRATIONS_AFTER:-20261103000039}"
MIG_DIR="$(cd "$(dirname "$0")/../.." && pwd)/supabase/migrations"
for f in "$MIG_DIR"/*.sql; do
  version="$(basename "$f" | cut -d_ -f1)"
  if [ "$version" -gt "$AFTER" ]; then
    if docker exec -i "$CONTAINER" psql -U postgres -d "$TEST_DB" -1 -v ON_ERROR_STOP=1 -q <"$f" >/dev/null 2>&1; then
      echo "Applied migration $(basename "$f") to $TEST_DB"
    else
      echo "Skipped migration $(basename "$f") (already in the dump or failed; check with psql if unexpected)"
    fi
  fi
done

verify() { docker exec -i "$CONTAINER" psql -U postgres -d "$TEST_DB" -Atq -c "$1"; }
missing=0
for f in generate_dose_events confirm_dose_slot consume_stock claim_consent_request; do
  n="$(verify "select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and p.proname='${f}'")"
  [ "$n" -ge 1 ] || { echo "MISSING function public.${f}" >&2; missing=1; }
done
for t in dose_events checkins consent_requests phone_opt_outs whatsapp_events care_recipients notifications profiles; do
  n="$(verify "select count(*) from information_schema.tables where table_schema='public' and table_name='${t}'")"
  [ "$n" -ge 1 ] || { echo "MISSING table public.${t}" >&2; missing=1; }
done
rows="$(verify "select count(*) from public.profiles")"
if [ "$missing" -ne 0 ]; then echo "Schema load incomplete." >&2; exit 1; fi
if [ "$rows" -ne 0 ]; then echo "Unexpected data in the test database (profiles=$rows)." >&2; exit 1; fi

echo "OK: $TEST_DB is ready (schema only, no data). From inside Docker Maven it is host.docker.internal:54322/$TEST_DB."
