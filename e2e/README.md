# ClassPool E2E tests

Playwright tests spanning both `apps/api` and `apps/web` — see `ARCHITECTURE.md`'s
directory-ownership note for why this lives at the repo root rather than inside
either app.

Currently one spec: `tests/first-technical-milestone.spec.ts`, which is PRD
§18.1's own first technical milestone, executed literally — an organizer
creates a class, shares the link, a parent joins, and both see the same live
class pool — plus a check that the PWA manifest/service worker are wired up.

## Running locally

You need Postgres, Redis, the API, and the web app all running first — this
suite doesn't start them for you (see [CI](#ci) for why: the API's stdout has
to be captured to a file the tests read magic-link tokens from, which is
easier to wire up as separate steps than inside Playwright's `webServer`
option).

```bash
# 1. Postgres + Redis
docker compose -f ../infra/docker-compose.yml up -d
# (or point at local system services — see apps/api/README.md if Docker
# egress is restricted in your environment)

# 2. Backend — note stdout redirected to a file
cd ../apps/api
SESSION_COOKIE_SECURE=false mvn spring-boot:run > /tmp/classpool-api.log 2>&1 &

# 3. Frontend
cd ../apps/web
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080/api/v1 npm run dev -- -p 3000 &

# 4. Run the tests
cd ../../e2e
npm install
API_LOG_FILE=/tmp/classpool-api.log npx playwright test
```

If `npx playwright install` can't reach `cdn.playwright.dev` in your
environment (sandboxed CI, restricted egress), point at a pre-installed
Chromium instead:

```bash
PLAYWRIGHT_CHROMIUM_PATH=/path/to/chrome API_LOG_FILE=/tmp/classpool-api.log npx playwright test
```

## Why magic-link auth is tested by reading a log file

V1's `LoggingEmailSender` (see `apps/api/README.md`) logs the magic-link
email instead of sending it — there's no real inbox to check in E2E yet.
`helpers/magic-link.ts` tails the API's captured stdout for the most recent
link emailed to a given address. This is a stopgap for V1, not the answer
long-term: once a real email provider (or a test inbox like Mailhog) is wired
up alongside later phases, replace this with actually receiving the email.

## CI

`.github/workflows/ci.yml`'s `e2e` job runs this against real GitHub Actions
Postgres/Redis services and the built API jar + `next build`/`next start` —
unlike this sandbox, Actions runners have unrestricted Docker/CDN access, so
CI installs Playwright's own browsers normally rather than needing the
`PLAYWRIGHT_CHROMIUM_PATH` override.
