# Chhaon backend (Java Spring Boot)

The Chhaon API, being rewritten from the Python FastAPI service (`../api/`) into Java Spring
Boot to match the Smart Gurukul stack. Deploys to AWS (EC2 + S3 + SSM) in **ap-south-1 (Mumbai)**.

- **Stack:** Spring Boot 3.4 · Java 21 · Maven · Flyway · Spring Security + JWT (jjwt) · Postgres (Supabase).
- **Auth:** custom JWT — the app owns identity (`profiles` + `user_credentials`); Supabase Auth
  and RLS are no longer used. Access control lives in the app layer.

## Status

Scaffold / foundation (done):
- Project, config, health endpoint.
- Spring Security + JWT (`security/`), stateless.
- Auth vertical: `POST /api/v1/auth/signup`, `POST /api/v1/auth/login`.
- `profiles` entity + Flyway `V1__custom_auth.sql` (drops the `profiles → auth.users` FK, adds
  `user_credentials`).
- Deploy pipeline (`.github/workflows/backend-*.yml`, `deploy/aws/*`) — inert until AWS exists.

Parent profiles / care recipients (done): `GET/POST /api/v1/recipients`, `GET/PUT/DELETE
/api/v1/recipients/{id}`. Membership scoping enforced in the app layer (RLS is bypassed by the
`postgres` role). Phone normalized to E.164; timezone/blood-group/list validation; plan-limit
pre-check. During transition, DB triggers still enforce owner-member creation, consent-null,
`limit_parents`, phone-change consent reset, self-profile guards (their errors are translated);
these move fully into Java at cutover. TODO: trigger WhatsApp consent on create/phone-change and
purge storage files on delete (await the WhatsApp/storage ports).

Medicines + schedules (done): `GET/POST /api/v1/recipients/{recipientId}/medicines`,
`PUT/PATCH/DELETE /api/v1/medicines/{id}`, `POST /api/v1/medicines/{id}/stock` (delta).
App-layer scoping by circle membership; name/dosage/instructions/photo-path validation; 1-12
distinct times; schedule reconciliation on update (keeps history); stock as clamped deltas;
active/critical flags. DB triggers still enforce caps + low-stock notifications (errors
translated). TODO: storage photo upload/signed URLs + purge on delete; dose-event generation
(with the scheduler port).

Still to port from FastAPI (feature by feature):
dose scheduler (`@Scheduled`), WhatsApp send + webhook, daily check-ins, reports + AI (anthropic-java),
appointments, family invites, emergency links, plans/limits, notifications.

## Run locally

Requires JDK 21 and Maven. Secrets come from `api-java/.env.local` (gitignored).

```sh
cd api-java
set -a; . ./.env.local; set +a      # loads SPRING_DATASOURCE_* and APP_JWT_SECRET
mvn spring-boot:run
```

> ⚠️ **Flyway runs on startup and will alter the connected database** (V1 drops the
> `profiles_id_fkey` and creates `user_credentials`). Point `.env.local` at a dev/branch
> database first, or confirm before running against the shared Supabase project. Existing users
> have no password row yet, so they can't log in via the new flow until one is set.

## Deploy

Push to `main` touching `api-java/**` → `backend-ci.yml` tests, `backend-deploy.yml` builds the
fat JAR, uploads to S3, and deploys to the EC2 box via SSM (swap JAR, restart, health-check,
auto-rollback). See `deploy/aws/` for the on-box script, systemd unit, nginx config, and the
one-time `provision-server.sh`. Required GitHub Secrets are listed in `backend-deploy.yml`.

**Stable IP:** the EC2 box uses an **Elastic IP** (allocated + associated during provisioning)
so its public IP never changes on stop/start/replace. DNS (`api.chhaon.site` A record) and the
TLS cert point at the Elastic IP, set once. Deploys never change the IP (they swap the JAR on the
running box); the Elastic IP covers instance stop/start/replacement. See `provision-server.sh`.
