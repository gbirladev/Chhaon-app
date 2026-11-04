#!/usr/bin/env bash
# Runs Maven inside Docker (there is no JDK or Maven on the Mac).
#   api-java/scripts/mvn-docker.sh -B test
#   api-java/scripts/mvn-docker.sh -B -DskipTests package
# The host's local Supabase Postgres is reachable from the container as host.docker.internal:54322.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$HOME/.m2"
exec docker run --rm \
  -v "$HERE":/app -w /app \
  -v "$HOME/.m2":/root/.m2 \
  --add-host=host.docker.internal:host-gateway \
  ${TEST_DB_URL:+-e TEST_DB_URL} ${TEST_DB_USER:+-e TEST_DB_USER} ${TEST_DB_PASSWORD:+-e TEST_DB_PASSWORD} \
  maven:3.9-eclipse-temurin-21 mvn "$@"
