# Multi-tenant Barber Booking SaaS

[![CI](https://github.com/emirhan-coban/multi-tenant-saas/actions/workflows/ci.yml/badge.svg)](https://github.com/emirhan-coban/multi-tenant-saas/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4-6DB33F)
![React 19](https://img.shields.io/badge/React-19-61DAFB)
![PostgreSQL RLS](https://img.shields.io/badge/PostgreSQL-RLS-336791)
![License: MIT](https://img.shields.io/badge/License-MIT-green)

A multi-tenant appointment platform for independent barbers. Customers pick a chair in an
interactive **3D shop** and book a slot; shop owners approve requests, manage staff, services and
working hours, and see reports. Turkish UI, English code-level docs below
([Türkçe özet](README.tr.md)).

**Live demo:** _TODO: add URL_ · **Demo owner login:** `demo@berber.test` / `Demo1234`
(demo data is reset periodically; do not enter real personal data)

<!-- Add screenshots after capturing them (see docs/deploy-free.md):
![3D seat picker](docs/media/3d-booking.png)
![Owner dashboard](docs/media/dashboard.png)
-->

## Highlights

- **Tenant isolation enforced in the database.** PostgreSQL Row Level Security with a
  non-superuser `saas_app` role; the tenant is derived from the access token and set per
  connection (`TenantAwareDataSource`). Integration tests run against a real Postgres as the
  restricted role, so RLS is exercised by every test.
- **Race-safe booking.** Row locks, idempotency keys and version-checked reschedule/cancel keep
  double-booking out under concurrency.
- **Trust-based approval flow.** First-time customers go to an owner/manager approval queue; after
  a verified first visit a customer becomes `TRUSTED` and later bookings auto-confirm (policy and
  limits are per tenant).
- **Versioned 3D shop (Three.js / react-three-fiber).** Draft, publish and rollback of shop scenes,
  chair-to-staff mapping, list/3D switch that keeps the booking draft.
- **Production-minded operations.** Transactional outbox with ShedLock, Redis/Bucket4j rate limiting,
  immutable audit log, plan quotas, KVKK/GDPR export and deletion, PWA with Web Push, Prometheus
  metrics behind a token.
- **Two separate identity systems** (staff vs. customers), each with access/refresh tokens and
  session versioning.
- **Quality gates.** 170+ backend and 120+ frontend tests, 34 Flyway migrations, CI matrix over two
  time zones, production image builds and a compose smoke test.

## Architecture

```
 Browser / PWA ── nginx ── React 19 + Vite SPA (TanStack Query, Three.js)
                     │
                     └──► Spring Boot 4 (Java 21) ── PostgreSQL 16 (RLS, Flyway)
                                   │
                                   └──────────────► Redis (rate limits, shared counters)
```

| Area | Where |
| --- | --- |
| Backend modules | `backend/src/main/java/com/saas/backend/{auth,booking,appointment,customer,billing,...}` |
| Migrations | `backend/src/main/resources/db/migration` |
| Web client | `web/src` (`api/`, `query/`, `router/`, `screens/`) |
| Ops, deploy, backups | `ops/` |
| Design notes per phase (Turkish) | `docs/` — start with [`development-notes.tr.md`](docs/development-notes.tr.md) |
| API contract | [`docs/contracts/booking-v1.openapi.yaml`](docs/contracts/booking-v1.openapi.yaml) |

## Run locally

Requires Docker.

```bash
cp .env.example .env
docker compose --profile app up -d
# Frontend: http://localhost:5173/app   Backend health: http://localhost:8081/api/health
```

Tests:

```bash
./backend/mvnw -f backend/pom.xml test     # Testcontainers: needs Docker
cd web && npm ci && npm test && npm run build
```

## Deploying on free tiers

Supabase (Postgres) + Upstash (Redis) + Cloudflare Pages (frontend) + a free container host for the
backend. Step-by-step: [`docs/deploy-free.md`](docs/deploy-free.md) and
[`docs/cloud-database.md`](docs/cloud-database.md).

## License

[MIT](LICENSE)
