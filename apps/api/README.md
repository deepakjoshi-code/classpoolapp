# ClassPool API

Spring Boot 3 / Java 21 backend implementing `contracts/openapi.yaml`'s Phase 1 (PWA shell + auth)
and Phase 2 (schools/classes/memberships) surface — see `ARCHITECTURE.md` §4 and `docs/PRD.md`
§17.3 for the build-order this corresponds to.

## Running locally

```bash
# from the repo root
docker compose -f infra/docker-compose.yml up -d   # Postgres 16 + Redis 7
cd apps/api
mvn spring-boot:run
```

The app listens on `:8080` by default (`PORT` env var to change it). Flyway applies
`infra/db/migrations/V1__initial_schema.sql` on startup — that file is the single source of
truth for the schema; nothing under `apps/api/src/main/resources` duplicates it (see "Flyway"
below).

Config is entirely environment-variable driven (see `src/main/resources/application.yml`):

| Env var | Default | Purpose |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/classpool`, `classpool`, `classpool_dev` | Matches `infra/docker-compose.yml` |
| `REDIS_HOST`, `REDIS_PORT` | `localhost`, `6379` | Session + magic-link token store |
| `APP_BASE_URL` | `http://localhost:8080` | Used to build the magic-link verify URL and the Google OAuth2 redirect URI |
| `WEB_BASE_URL` | `http://localhost:3000` | Used to build the `joinUrl` returned from invite creation and the magic-link email's destination (both point at the Next.js app, a different origin/port in dev) |
| `CORS_ALLOWED_ORIGINS` | `WEB_BASE_URL`'s value | Comma-separated list of origins allowed to make credentialed cross-origin requests — see "CORS" below |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | unset | Real values needed only to actually complete a Google sign-in — see "Google OAuth2" below; the app starts fine without them |
| `SESSION_COOKIE_SECURE` | `true` | Set `false` for local HTTP-only dev if your browser rejects a `Secure` cookie over plain `http://localhost` |

## Running tests

```bash
cd apps/api
mvn test
```

- **Unit tests** (`src/test/java/.../service/*Test.java`): JUnit 5 + Mockito, no Spring context —
  fast, one class per service.
- **Integration tests** (`src/test/java/app/classpool/api/*IntegrationTest.java`): `@SpringBootTest`
  + Testcontainers, against a real ephemeral Postgres 16 and a real ephemeral Redis 7 (both via
  `AbstractIntegrationTest`) — **requires a working Docker daemon** reachable by the JVM running
  Maven. No H2/in-memory substitute is used, on purpose (`ARCHITECTURE.md` §2): the whole point of
  the PRD §14 tenant-isolation test is catching real SQL/constraint behavior, which an in-memory
  DB can silently paper over.

  `CrossTenantAuthorizationIntegrationTest` is the most important test in this codebase — it is
  the literal PRD §14 bar ("Changing a class, pool, membership or requirement ID in an API request
  must never allow a parent from Class A to read or modify Class B") exercised end-to-end over
  HTTP against a real database.

If your Docker daemon needs the resource-reaper container disabled (some sandboxed/CI
environments block it), set `TESTCONTAINERS_RYUK_DISABLED=true`.

## Session mechanism

**Opaque, high-entropy, Redis-backed session tokens carried in an HttpOnly cookie** (name
`CLASSPOOL_SESSION`, matching the OpenAPI `sessionCookie` security scheme).

- On successful auth (magic-link verify or Google callback), `SessionService.create(userId)`
  generates a 256-bit random token, stores `session:<token> -> userId` in Redis with a 30-day TTL,
  and the token is set as the cookie value (`HttpOnly`, `SameSite=Lax`, `Secure` unless
  `SESSION_COOKIE_SECURE=false`).
- Every request, `SessionAuthenticationFilter` reads the cookie, resolves it against Redis, and —
  if found — sets a Spring Security `Authentication` whose principal is the caller's `UUID`.
  Controllers read it via `@AuthenticationPrincipal UUID callerUserId`.
- Nothing about the user is encoded in the cookie itself (no JWT, no signing) — a guessed/stolen
  cookie value is useless without the matching Redis entry, and logout is a single key delete
  (`SessionService.invalidate`).
- Spring Security's own `HttpSession`/`JSESSIONID` machinery is unused
  (`SessionCreationPolicy.STATELESS`) — Redis is the actual session store.

This was chosen over Spring Session because the whole mechanism is ~80 lines
(`SessionService` + `SessionAuthenticationFilter` + `SessionCookieHelper`) and needs no extra
dependency beyond `spring-boot-starter-data-redis`, which the app already needs for magic-link
tokens (see below) — matching `ARCHITECTURE.md` §1's "Redis, used for session/rate-limit state."

**Magic-link tokens** use the identical pattern (`MagicLinkService`): `magic-link:<token> ->
email` in Redis with a 15-minute TTL. Single-use is enforced by reading with `GETDEL` (Redis
6.2+, via Spring Data Redis's `ValueOperations.getAndDelete`) — a second verify attempt for the
same token finds nothing, because the first successful read already deleted the key. This is
covered by `MagicLinkAuthIntegrationTest.magicLinkToken_isSingleUse_secondVerifyAttemptFails`.

**CSRF** is disabled (`SecurityConfig`): this is a same-origin JSON API with no cookie-driven HTML
form submissions, and the session cookie is `SameSite=Lax`, which already blocks the classic
cross-site-POST CSRF case. A production hardening pass could still add a double-submit CSRF token
if the frontend ever grows a cookie-authenticated non-JSON form flow — flagged here rather than
built now, since nothing in the Phase 1-2 contract needs it.

## CORS

apps/web's API client (`src/lib/api/client.ts`) sends `credentials: "include"` whenever it's
pointed at an absolute `NEXT_PUBLIC_API_BASE_URL` — its own README calls this out as the setup
"for local dev where the two apps run on different ports" (this API on `:8080`, the Next.js dev
server on `:3000` by default). A credentialed cross-origin request needs an explicit
`Access-Control-Allow-Origin` (never `*`) plus `Access-Control-Allow-Credentials: true`, or the
browser blocks it before it ever reaches a controller — `SecurityConfig`'s
`corsConfigurationSource` bean provides that, allowing the origin(s) in `CORS_ALLOWED_ORIGINS`
(defaults to just `WEB_BASE_URL`).

## Email delivery

`EmailSender` is an interface (`service/email/EmailSender.java`); the only implementation wired up
is `LoggingEmailSender`, which logs the magic-link email instead of sending it — there is no live
AWS SES access in this environment. A real SES-backed `EmailSender` is a drop-in: implement the
interface, register it as the `@Primary`/only bean (e.g. behind a Spring profile), and nothing in
`AuthService` changes.

## Google OAuth2

Configured through Spring Security's OAuth2 Client SPI (`ClientRegistration` /
`ClientRegistrationRepository`), per `ARCHITECTURE.md` §2 — see `config/OAuth2Config.java`.

One deliberate deviation from the most common Spring Boot setup: the `google` `ClientRegistration`
is built **programmatically** in `OAuth2Config`, not via the usual
`spring.security.oauth2.client.registration.google.*` property block. Spring Boot's
property-driven `OAuth2ClientAutoConfiguration` **hard-fails application startup** if that
block's `client-id` is blank — which it always is in this sandbox (no live Google app configured,
as expected) and in most local/CI environments too. Building the registration directly avoids
that: the app boots cleanly with placeholder credentials, and `GoogleOAuthService` only refuses
(with a clear 400) at the moment someone actually calls `/auth/google/callback` with no real
`GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` configured.

`GoogleOAuthService` also does the code→token and token→userinfo HTTP calls directly
(`RestClient`) rather than via Spring Security's built-in `oauth2Login()` filter chain, because the
OpenAPI contract specifies a custom callback shape (`GET /auth/google/callback` returning a JSON
`Session` body) rather than Spring Security's default redirect-based
`/login/oauth2/code/{registrationId}` handling.

The *initiation* half, though, **is** stock Spring Security: `GET /oauth2/authorization/google`
(what the frontend's "Continue with Google" button links to — Spring Security's conventional path,
and apps/web is already built against it) is served by a manually-added
`OAuth2AuthorizationRequestRedirectFilter` (`SecurityConfig`), which redirects to Google and
stashes the generated `state` via a shared `AuthorizationRequestRepository` bean
(`OAuth2Config`, `HttpSessionOAuth2AuthorizationRequestRepository` — the one place in this app that
briefly uses a servlet session, scoped just to the OAuth2 handshake, separate from ClassPool's own
Redis-backed session cookie). `GoogleOAuthService.handleCallback` reads and removes that stashed
request on the way back and rejects the callback if `state` doesn't match — standard OAuth2 CSRF
protection, and single-use since the repository entry is removed on first read.

## Schema ownership / Hibernate

`spring.jpa.hibernate.ddl-auto` is **`none`**, not `validate`. Flyway
(`infra/db/migrations/V1__initial_schema.sql`) is the exclusive source of truth for the schema, as
`ARCHITECTURE.md` §1 specifies. `validate` was tried and rejected during development: Hibernate's
schema validator has no concept of Postgres extension column types (`citext`, `text[]`) and
false-positives against them (e.g. "expecting varchar(255), found citext") even though both read
and write correctly at runtime — Flyway already guarantees the schema is correct, so asking
Hibernate to *also* police it on boot only added a dialect-quirk false alarm, not real safety.

## Dedup fuzzy-match

`GET /schools/search` and the `dedupWarning` on `POST /classrooms` are both backed by native
`pg_trgm` `similarity()` queries (`SchoolRepository.fuzzySearch`,
`ClassroomRepository.fuzzySearchInSchoolYear`) against the GIN trigram indexes already defined in
the V1 migration (`idx_school_name_trgm`, `idx_classroom_grade_teacher_trgm`). Threshold is 0.3
(pg_trgm's conventional "meaningfully similar" cutoff). The classroom-level check is scoped to the
same `school_year` — the PRD §2.3 scenario is two parents at the *same school, same year*
unknowingly starting the same class twice.

## Late-join flag

`PoolGateway` (`service/PoolGateway.java`) is a narrow, read-only `JdbcTemplate` query against the
`pool` table — deliberately **not** a JPA entity, since Phase 1-2 has no pool-creation endpoint
(`pool` is a Phase 3+ table per the task scope). It answers one question: has this classroom's
pool already left `OPEN_FOR_CONTRIBUTIONS`? `InviteService.join` uses it to set
`Membership.lateJoin = true` per PRD §13.3. In Phase 1-2 this is usually moot (no pool rows exist
yet), but the check is written correctly for when Phase 3+ starts creating pools.

## Authorization model (PRD §14)

Every classroom-scoped endpoint resolves the classroom, then checks
`MembershipRepository.existsByClassroom_IdAndParentUserId(classroomId, callerUserId)` before
returning anything — see `ClassroomService.getForCaller`. A classroom that exists but the caller
has no membership on returns **403**, never a 404 (which would leak existence) and never partial
data. `CrossTenantAuthorizationIntegrationTest` is the dedicated proof of this.

## Package layout

```
app.classpool.api
├── domain/       JPA entities — one per Phase 1-2 table (AppUser, Household, Student, School,
│                 SchoolYear, Classroom, Membership, Invite) plus their enums
├── repository/   Spring Data JPA repositories, including the native pg_trgm dedup queries
├── service/      Business logic — auth, schools, classrooms, invites, household dashboard,
│                 the Redis-backed session/magic-link stores, email boundary
├── security/     Cookie-based session auth filter + cookie read/write helper
├── config/       SecurityConfig (filter chain, public-endpoint allowlist), OAuth2Config
├── dto/          Request/response records matching contracts/openapi.yaml's schemas exactly
├── web/          @RestController classes, one per resource group in the OpenAPI paths
└── exception/    ApiException hierarchy (400/401/403/404) + a @RestControllerAdvice
```
