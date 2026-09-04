# ClassPool

Turn a class supply list into a collective reuse, exchange, residual-demand,
and group-purchase plan — families buy only what the class still needs after
reuse and contribution.

- **Product spec**: [`docs/PRD.md`](docs/PRD.md) — the full working
  specification, including a running gap-log of decisions made beyond the
  original blueprint.
- **Technical decisions**: [`ARCHITECTURE.md`](ARCHITECTURE.md) — stack
  choices and reasoning, repo layout, testing strategy, current build-order
  scope.

## What's implemented

**Phase 1 (PWA shell + auth) and Phase 2 (schools/classes/memberships)** of
the 12-phase V1 build order in `docs/PRD.md` §17.3 — enough to hit the PRD's
own first technical milestone (§18.1): an organizer creates a class, shares a
link, a parent joins, and both see the same live class pool. Verified
end-to-end in [`e2e/`](e2e).

Phases 3–12 (household inventory, the exchange pool, the allocation and
residual-demand engines, the bulk-pack optimizer, payments, distribution, AI
ingestion, notifications/analytics) are follow-on work, same pattern:
contract first, then implementation, then tests.

## Repository layout

```
docs/PRD.md              product spec
ARCHITECTURE.md           technical decisions
contracts/openapi.yaml    API contract — source of truth for both apps below
infra/                    Flyway migrations (full V1 schema) + local docker-compose
apps/api/                 Spring Boot backend — see apps/api/README.md
apps/web/                 Next.js PWA frontend — see apps/web/README.md
e2e/                      Playwright tests spanning both apps — see e2e/README.md
.github/workflows/ci.yml  lint/unit/integration/E2E on every push and PR
```

## Running it locally

```bash
# Postgres + Redis
docker compose -f infra/docker-compose.yml up -d

# Backend (localhost:8080)
cd apps/api && mvn spring-boot:run

# Frontend (localhost:3000), in another shell
cd apps/web && NEXT_PUBLIC_API_BASE_URL=http://localhost:8080/api/v1 npm run dev
```

See `apps/api/README.md` and `apps/web/README.md` for test commands, and
`e2e/README.md` for the end-to-end suite (including how magic-link sign-in is
tested without a real inbox in this phase).
