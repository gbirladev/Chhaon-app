# Java backend tests (`api-java/`)

Until now the Java backend had no tests. This page explains how the tests are built, how to run them, and
(as a **proposal, not yet wired**) how CI could run them.

## What the tests run against

The business logic lives largely in Postgres functions (`generate_dose_events`, `confirm_dose_slot`,
`consume_stock`, `claim_consent_request`, ...) defined by `supabase/migrations/*.sql`. So the integration
tests use a **real Postgres**, not H2.

* The database is **`chhaon_java_test`**, a throwaway database inside the local Supabase Postgres
  (container `supabase_db_carelink`, port 54322).
* `api-java/scripts/make-test-db.sh` (idempotent) drops and re-creates it from a **schema-only** dump
  (`pg_dump -s`) of the local `postgres` database. No data is copied except the pricing-plans catalogue
  (reference data that `profiles.plan` points to). The shared `postgres` database is only read.
  The load prints ~200 harmless "permission denied / must be able to SET ROLE" lines (Supabase-internal
  ownership and default privileges); the script verifies the app tables and functions exist.
* Spring runs Flyway on start, which baselines the schema and applies `V1__custom_auth.sql` (profiles are
  no longer tied to `auth.users`, so tests can insert caregivers directly).
* `Fixtures` (test code) refuses to run unless the connected database name contains `test`.

## Running

No JDK or Maven is installed on the Mac, so Maven runs in Docker:

```bash
cd <repo>
npx supabase start                      # once; local Supabase must be up
api-java/scripts/make-test-db.sh        # creates/refreshes chhaon_java_test (about 10 s)
api-java/scripts/mvn-docker.sh -B test  # all tests
api-java/scripts/mvn-docker.sh -B test -Dtest=ReplyClassifierTest   # one class
api-java/scripts/mvn-docker.sh -B -DskipTests package               # just build the jar
```

`mvn-docker.sh` is a thin wrapper for
`docker run --rm -v api-java:/app -w /app -v ~/.m2:/root/.m2 --add-host=host.docker.internal:host-gateway maven:3.9-eclipse-temurin-21 mvn ...`.
From inside the container the host's Postgres is `host.docker.internal:54322`.

Environment overrides: `TEST_DB_URL` (default `jdbc:postgresql://host.docker.internal:54322/chhaon_java_test`),
`TEST_DB_USER`, `TEST_DB_PASSWORD` (default `postgres`/`postgres`, the local Supabase defaults).

If the database is not reachable (or does not look like a test database) every database test is
**skipped with a message** (JUnit `ExecutionCondition`), so a machine without it does not fail the build.
The pure unit tests (`ReplyClassifierTest`, text tests, signature tests) always run.

## How tests are isolated

* `DbTest` is the base class: `@SpringBootTest`, profile `test` (`src/test/resources/application-test.properties`),
  `chhaon.scheduler.enabled=false`, and a `RecordingWhatsAppSender` bean (`@Primary`) that records every
  message instead of sending it (and can be told to fail).
* Every test creates its own caregiver, circle, parent (random phone number) and medicines through
  `Fixtures`, and `@AfterEach` deletes them again (the circle delete cascades), plus the opt-out,
  consent-request and webhook-event rows for the phones it used.
* The scheduler is only ever run as `tick(recipientId)` (scoped), never for everyone.
* Times are relative to now (and a time zone where "now" is not near midnight is chosen), so the tests do
  not depend on the clock of the day.

## What is covered

* `SchedulerTickCharacterizationTest`: pins down the 6-step tick as it behaves today (generate, send due,
  unsent become skipped, missed, check-ins sent, check-ins missed, plus consent / STOP / failure /
  scope behaviour).
* The reply pipeline: see `docs/java-whatsapp-replies.md`.

## CI proposal (NOT implemented; `.github/workflows/*` was not changed)

A job that builds the database the same way production-like tooling does, then runs Maven against it:

```yaml
name: api-java tests
on:
  pull_request:
    paths: ["api-java/**", "supabase/migrations/**"]
jobs:
  test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: supabase/postgres:17.6.1.003   # pin to the version `supabase start` uses locally
        env: { POSTGRES_PASSWORD: postgres }
        ports: ["54322:5432"]
        options: >-
          --health-cmd "pg_isready -U postgres" --health-interval 5s --health-retries 20
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "21", cache: maven }
      - name: Create the test database from the migrations
        run: |
          export PGPASSWORD=postgres
          psql -h localhost -p 54322 -U postgres -c "create database chhaon_java_test"
          for f in supabase/migrations/*.sql; do
            psql -h localhost -p 54322 -U postgres -d chhaon_java_test -v ON_ERROR_STOP=1 -q -f "$f"
          done
      - name: Test
        working-directory: api-java
        env:
          TEST_DB_URL: jdbc:postgresql://localhost:54322/chhaon_java_test
        run: mvn -B test
```

Open points for whoever wires it: (1) the `supabase/postgres` image already contains the Supabase roles and
the `auth` / `storage` schemas the migrations refer to, but the exact image tag must match local
`supabase start`; (2) the migrations are applied directly (not through the Supabase CLI), so verify they
apply cleanly on that image once; (3) the real production workflow deploys on any push touching
`api-java/**`, so keep the test workflow separate and **before** any deploy job (`needs:`).
