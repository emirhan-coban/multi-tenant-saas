# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Multi-tenant barber-shop booking SaaS: Spring Boot (Java 21) backend (`backend/`), React 19 + Vite + Three.js web client (`web/`), PostgreSQL + Redis. Docs and UI strings are mostly Turkish; per-phase design notes live in `docs/phase-*.md` and `docs/emirhan-plan.md` (the implementation plan / source of truth for ordering). OpenAPI contract: `docs/contracts/booking-v1.openapi.yaml`.

## Commands

Full stack in Docker (dev profile: Postgres, Redis, backend, frontend; copy `.env.example` to `.env` first, keep an existing `.env`):

```bash
docker compose --profile app up -d   # frontend :5173/app, backend :8081/api/health
```

Backend (Java 21, Maven wrapper). Tests use Testcontainers (ephemeral Postgres 16 + Redis 7), so Docker must be running:

```bash
./backend/mvnw -f backend/pom.xml test
./backend/mvnw -f backend/pom.xml test -Dtest=ClassName#method   # single test
```

CI runs backend tests under both `TZ=UTC` and `TZ=Europe/Istanbul`; keep time logic timezone-safe (compare `Instant`s).

Web (Node 22):

```bash
cd web && npm ci
npm run dev        # Vite; API defaults to http://localhost:8080, override with VITE_API_URL
npm run build      # tsc -b && vite build (this is the type check)
npm test           # vitest run
npx vitest run tests/services.test.tsx   # single test file
```

`*.command` scripts at the root (`calistir`, `durdur`, `test`, `webtest`) are macOS double-click helpers around docker compose.

## Architecture

**Tenancy is enforced in the database via Postgres RLS**, not only in application code.
- The app connects as the restricted role `saas_app` (NOSUPERUSER, NOBYPASSRLS; created by `ops/db/01-app-role.sh` / `ops/cloud/setup-supabase.sh`). Flyway runs migrations as the schema owner (`DB_OWNER_USER`), never as `saas_app`.
- Tenant context is the Postgres setting `app.current_tenant`. `config/TenantAwareDataSource` sets it on connection checkout from `TenantContext` (derived from the access token's tenant, never from request input) and clears it on return. Background jobs (outbox, retention, metrics) have no HTTP context and call `set_config('app.current_tenant', ..., true)` inside their own transactions.
- Any new tenant table needs RLS policies (see migrations `V2`/`V3`/`V20`/`V32`); `PostgresTestBase` creates `saas_app` so integration tests prove RLS is actually applied. Don't "fix" a test by connecting as owner.
- Schema is Flyway-only (`backend/src/main/resources/db/migration/V*__*.sql`, `ddl-auto: validate`). CI fails on duplicate version numbers — take the next free `V<n>`.

**Two separate identity systems**: staff/management users (`auth`, `membership`, roles OWNER/MANAGER/STAFF per tenant, `/api/*`, `/app` UI) and end customers (`customer`, own access/refresh tokens, per-tenant trust profile, `/customer` and public `/book/{tenantSlug}` UI).

**Booking domain** (`booking`, `appointment`, `calismasaati`, `hizmet`, `personel`, `izinkaydi`): customers request slots; FIRST-visit / non-trusted customers go to an OWNER/MANAGER approval queue, `TRUSTED` customers (after a verified first visit and completed appointment) auto-confirm if tenant policy allows. Slot races are handled server-side (row locks, idempotency keys, version-checked reschedule/cancel). Weekday convention: 0=Monday … 6=Sunday. Versioned 3D shop scenes (draft/publish/rollback) drive the Three.js seat picker.

**Operations**: transactional outbox with ShedLock, Redis/Bucket4j rate limiting, immutable audit log, plan quotas (`billing`, FREE/PRO), KVKK export/deletion (`retention`, `account`), feature flags (`flags`), Web Push/PWA (`push`, VAPID keys required in prod), metrics gated by `APP_OPS_METRICS_TOKEN`. Email is off by default (console); Gmail setup in `docs/gmail-email.md`.

**Web** (`web/src`): `api/` = Axios functions + `client.ts` interceptor with a single-flight refresh lock (access token in memory, refresh token in browser storage); `query/` = TanStack Query client, tenant-scoped keys (`keys.ts`) and hooks (`management.ts`) that must run under the authenticated `TenantProvider`; `router/navigation.ts` is the single role→menu/route-guard definition (backend authorization is independent). Optimistic updates roll back only the affected item; mutations aren't auto-retried and reuse the same Idempotency-Key on network retry. Production nginx must fall back to `index.html` for SPA routes.

## Environments

- `.env` (local dev), `.env.cloud` (Supabase + Upstash; git-ignored, template `ops/env.cloud.example`), `docker-compose.{ci,staging,prod}.yml`, templates in `ops/env.{staging,prod}.example`. Deploy/rollback scripts in `ops/deploy/`.
- Cloud DB: must use the Supabase **Session pooler (port 5432)**, not the transaction pooler (6543), because tenant context is session state. See `docs/cloud-database.md` (`ops/cloud/setup-supabase.sh`, `ops/cloud/migrate-local-data.sh`).
