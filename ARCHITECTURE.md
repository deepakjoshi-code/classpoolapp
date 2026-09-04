# ClassPool — Architecture Decisions (V1)

Companion to `docs/PRD.md`. This is the technical decision record: what we're building V1 on, and why. PRD §15 already sketched a stack (Next.js, Java/Spring Boot, Postgres); this document commits to it concretely, with the reasoning, and adds what the PRD didn't specify (repo layout, testing strategy, CI, AI model choice).

## 1. Why this stack

**Backend: Java 21 + Spring Boot 3.**
The product's core IP is deterministic, correctness-critical logic over money and physical goods: the residual-demand equation (PRD §6), the bulk-pack optimizer (§7, a bin-packing/integer-optimization problem), and a two-state-machine model (Requirement × Pool, §13.2/13.3) that must never desync. This is exactly the kind of domain where a statically-typed, mature-tooling language earns its keep — the compiler catches a whole class of "wired the wrong enum" bugs before they reach a parent's bill, and the JVM ecosystem's testing tools (JUnit 5, Mockito, **Testcontainers** for real-Postgres integration tests) are the best fit for a system this test-critical. Java 21's virtual threads make the background-job-heavy parts (notifications, pricing lookups, AI extraction calls) cheap to write without reactive-programming complexity.

**Frontend: Next.js 15 (App Router) + TypeScript.**
Already the PRD's call (§15.1, §11), and correct: one codebase for the PWA across iPhone/Android/desktop, React Server Components keep the mobile-first pages fast, and the App Router's built-in support for a web app manifest + service worker fits the installable-PWA requirement (§11) directly.

**Database: PostgreSQL 16, migrated with Flyway.**
Relational fits this domain — the entity graph in PRD §13.1 (School → Classroom → Pool → Requirement → Allocation → Payment...) is fundamentally relational with real foreign-key integrity requirements (the "Class A can never read Class B" test in §14 is a tenant-isolation problem Postgres row-level patterns handle well). Flyway (not Hibernate auto-DDL) because every schema change should be a reviewable, versioned SQL file — non-negotiable for a system handling other people's money.

**Cache/jobs: Redis**, used for session/rate-limit state and as the backing store for background job queues (notification fan-out, AI extraction retries) — not stretched further than that in V1, per PRD §15.1's "where justified."

**Object storage: AWS S3** for uploaded requirement-list photos/PDFs, with signed upload URLs (PRD §14.2) so the backend never proxies raw file bytes.

## 2. What the PRD left implicit, decided here

**Auth**: Spring Security OAuth2 Client for Google Sign-In; a custom magic-link flow (signed, single-use, 15-minute-expiry token emailed via SES, exchanged server-side for a session) for email auth; Apple Sign-In stubbed in V1 scaffolding but not wired end-to-end in the first vertical slice (its client-secret-as-JWT setup is the fiddliest of the three and lowest-priority for the first pilot class per §18). No third-party auth vendor (Auth0/Clerk) — Spring Security's OAuth2 support plus a ~100-line magic-link implementation covers the V1 auth surface (§2.2) at zero added cost or vendor lock-in, revisit only if auth complexity grows past what a two-person team should own.

**API contract**: OpenAPI 3.1 is the source of truth (`contracts/openapi.yaml`), not code-first annotations. The frontend generates its TypeScript client/types from it (`openapi-typescript`) rather than hand-typing fetch calls — this is what lets backend and frontend be built in parallel without drifting, since both are building against the same file rather than against each other's evolving code.

**Testing strategy** (this is the answer to "backend testing — unit, functional" and "UI testing through automated tools"):
- Backend unit tests: JUnit 5 + Mockito, one test class per service, no Spring context (fast).
- Backend functional/integration tests: `@SpringBootTest` + **Testcontainers** spinning a real ephemeral Postgres — no H2/in-memory substitute, since the whole point of PRD §14's tenant-isolation test is catching real SQL/constraint behavior, which an in-memory DB can silently paper over.
- Frontend component tests: Vitest + React Testing Library.
- End-to-end: **Playwright**, not Selenium. Both automate real browsers; Playwright is the modern default for a Next.js/PWA stack specifically — auto-waiting (fewer flaky sleeps), first-class multi-browser support including mobile viewport emulation (this is a mobile-first PWA, §11), and it can assert service-worker registration and manifest installability directly, which Selenium has no native support for. Framed differently: same category of tool the brief asked for, better fit for this stack.
- CI: GitHub Actions — lint + unit tests on every push; Testcontainers integration tests + Playwright E2E on every PR to the default branch.

**AI model for requirement extraction (PRD §3.2, §15.2)**: **`claude-opus-5`** via the Anthropic Java SDK, using **structured outputs** (`.outputConfig(RequirementExtraction.class)`) so the model's response deserializes directly into the `Requirement` POJO — no manual JSON parsing, no schema drift between prompt and code. Document input (PDF/photo) goes in as a `DocumentBlockParam`/image content block alongside the extraction instruction. This is a single-call extraction task (not an agent — no multi-step tool use needed), run once per uploaded list, always followed by mandatory human review (§3.3) before anything is financially actionable — so the cost of a wrong extraction is bounded by that review step, and the task genuinely benefits from Opus-tier accuracy on messy handwriting/scanned text where a cheaper model would push more corrections onto the organizer. `effort: medium` as the starting point (extraction isn't long-horizon agentic reasoning), to be re-tuned against real accuracy data from the §18 pilot rather than guessed. AI never sees or touches money — the extraction service's only output is a `Requirement` draft in `NEEDS_REVIEW` state (§13.2); all downstream logic (allocation, billing, optimizer) stays deterministic Java, per the AI boundary table already in PRD §15.2.

## 3. Repository layout

```
classpoolapp/
├── docs/
│   └── PRD.md                      # product spec (existing)
├── ARCHITECTURE.md                 # this file
├── contracts/
│   └── openapi.yaml                # API source of truth — both apps build against this
├── infra/
│   ├── db/
│   │   └── migrations/             # Flyway SQL, versioned, matches PRD §13.1 entities
│   └── docker-compose.yml          # local Postgres + Redis for dev
├── apps/
│   ├── api/                        # Spring Boot backend — owns infra/db/migrations
│   └── web/                        # Next.js PWA frontend — owns nothing outside apps/web
└── .github/workflows/              # CI
```

Directory ownership is deliberate: `apps/api` and `apps/web` are built by independent workstreams against the shared `contracts/openapi.yaml` and `infra/db/migrations`, so backend and frontend implementation can proceed in parallel without touching each other's files.

## 4. Build order for this pass

Per PRD §17.3's vertical-slice build order, this pass targets **Phase 1 (PWA shell + auth) and Phase 2 (Schools/classes/memberships)** — enough to hit the PRD's own first technical milestone (§18.1: *"an organizer creates a class, shares a link, a parent joins, both install to Home Screen, both see the same live class pool"*), fully tested. The full V1 database schema (all entities from §13.1 plus the PM-update additions — `Household`, `Student`, `Invite`, `ClassReserve`, `Transfer`, `OrganizerStripeAccount`, `School.approvedEmailDomains`) is laid down now even though later phases' endpoints don't exist yet — cheaper to migrate once than to bolt on `Household`/`Student` after `Membership` already points straight at `ParentProfile` (the exact trap PRD §17's build-order update warns against).

Phases 3–12 (inventory, exchange pool, allocation engine, optimizer, payments, distribution, AI ingestion, notifications/analytics) are follow-on work, same pattern: contract first, then parallel implementation, then tests. Not claimed as done here.
