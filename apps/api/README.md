# ClassPool API

Spring Boot 3 / Java 21 backend implementing `contracts/openapi.yaml`'s Phase 1 (PWA shell + auth),
Phase 2 (schools/classes/memberships), Phase 3 (pools + manual requirements, no AI yet),
Phase 4 (household inventory — "Shop Your Home First"), Phase 5 (surplus contribution pool),
Phase 6/7 (allocation & residual-demand engine), and Phase 8 (bulk pack optimizer / purchase plan)
surface — see `ARCHITECTURE.md` §4 and `docs/PRD.md` §17.3 for the build-order this corresponds to.

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

## Pools and requirements (Phase 3) design notes

`Pool`/`Requirement` are now full JPA entities (`domain/Pool.java`, `domain/Requirement.java`) —
`PoolGateway`'s narrow JdbcTemplate query (see "Late-join flag" above) is unchanged and still
answers its one question via raw SQL, but every Phase 3 endpoint goes through the new
`PoolRepository`/`RequirementRepository` instead.

**Organizer-only check, extracted once.** `InviteService.create` already had an "is this caller an
ORGANIZER/CO_ORGANIZER on this classroom?" check; Phase 3 needed the identical check in three more
places (pool creation, requirement add/edit/remove, pool confirm). Rather than re-deriving it per
call site, it now lives as `MembershipRepository.hasOrganizerRole(classroomId, callerUserId)` (a
default method over a derived `existsBy...RoleIn` query), and `InviteService` was refactored onto
it too — see the method's Javadoc.

**`totalDemand` — schema gap found and fixed during integration review.** PRD §3.4 defines
aggregate class demand as `quantityPerStudent × confirmedStudentCount`, computed once at
pool-confirm time and expected to read as a stable snapshot afterward. The V1 migration's
`requirement` table had no column to persist that snapshot, so the first cut of this phase computed
`totalDemand` **live** on every read instead — which passed every test in this phase's scope
(including the exact worked example, 3 joined students × quantityPerStudent 4 → totalDemand 12),
but was a real correctness bug waiting to happen: a classroom gaining a Membership after its pool
left DRAFT (a late joiner, PRD §13.3, is the common case) would have silently changed an
already-"confirmed" total on the next read — exactly the moving-target-under-a-load-bearing-number
problem the Phase 6/7 residual-demand engine cannot tolerate.

Fixed via `infra/db/migrations/V2__pool_confirmed_student_count.sql`: one `integer` column on
`pool` (not `requirement` — every requirement in a pool is confirmed together against the same
student count, so one column on the pool suffices rather than duplicating it per requirement).
`PoolService.confirm()` sets `Pool.confirmedStudentCount` once, before the pool moves to
`OPEN_FOR_INVENTORY`; `RequirementAssembler` now derives `totalDemand` from that frozen field
instead of querying `MembershipRepository` at all. `PoolConfirmIntegrationTest`'s
`totalDemand_doesNotChange_whenAFamilyJoinsAfterConfirm` proves the fix: confirms a pool, joins a
new family, re-fetches, and asserts the total is unchanged.

**`Classroom.pools` summary.** `ClassroomAssembler` now also batches a `PoolResponse` list per
classroom (via `PoolRepository`/`PoolAssembler`), so every endpoint that serializes a `Classroom` —
`GET/POST /classrooms/{id}`, `GET /household/dashboard` via `MembershipAssembler` — gets the pools
summary for free, with no per-endpoint pool-fetching logic to duplicate or drift.

## Household inventory (Phase 4) design notes

New surface: `GET /pools/{poolId}/inventory`, `PUT
/pools/{poolId}/requirements/{requirementId}/inventory`, `GET /pools/{poolId}/inventory/summary` —
all three routes added to the existing `PoolController` (they're all pool-scoped, same as
`/pools/{poolId}/confirm`), delegating to a new `InventoryService`. Backed by a new
`ParentInventory` entity/`ParentInventoryRepository` over the V1 migration's already-present
`parent_inventory` table — no schema changes were needed for this phase.

**Only `owned_quantity` is touched.** `parent_inventory.surplus_offered_quantity` and `.condition`
are Phase 5 (surplus/reuse marketplace) columns; `ParentInventory` doesn't map them at all, so they
simply take their DB defaults (`0` and `null`) on every insert this phase makes. A later phase's
entity can add them without touching anything written here.

**Cross join for multi-student households.** `GET /pools/{poolId}/inventory`'s contract line "one
line per (requirement, student they have in this classroom)" is read literally: for a caller with
two students in the same classroom (twins), the response is `|requirements| x 2` lines, not one.
`InventoryService.getMyInventory` builds this as an actual in-memory cross join — the pool's
requirements (already a handful of rows per pool) times the caller's own `Membership` rows with a
non-null student on that classroom — rather than a single denormalized SQL join, since the
"student belongs to caller" filter is naturally expressed via `MembershipRepository` (the same
table every other tenant-isolation check already goes through) and the requirement/inventory row
counts here are small enough that doing the join in Java costs nothing measurable. Any
(requirement, student) pair with no `ParentInventory` row yet defaults to `ownedQuantity: 0` (a
parent shouldn't have to explicitly zero something out) rather than being omitted.

**Upsert key vs. "who wrote it".** The DB-unique key is `(requirement_id, student_id)`, not
`parent_user_id` — `ParentInventory.applyOwnedQuantity` refreshes `parentUserId` to whichever
caller most recently wrote the row on every PUT. This doesn't matter yet (only the student's own
Membership-holder can write it this phase — see below), but keeps the column meaningful once a
later phase lets more than one household member (e.g. two co-parents, each with their own
Membership grant on the same student) record against the same row.

**Authorization is per-student, not per-classroom.** Every other Phase 1-3 write checks "does the
caller have *any* Membership on this classroom" (`PoolService.requireMembership`/
`requireOrganizer`). Setting inventory needs a narrower check — `MembershipRepository
.findByClassroom_IdAndParentUserIdAndStudent_Id(classroomId, callerUserId, studentId)` — matching
the contract's "never lets one household record inventory for another's child" (PRD §14 in
miniature, one level down from classroom to student). `GET /pools/{poolId}/inventory` still uses
the classroom-wide membership check (any member may call it for their own household), then derives
"own students" itself from the caller's own Membership rows.

**Clamping is defense-in-depth, not just a DTO constraint.** `SetInventoryRequest.ownedQuantity`
carries the contract's `minimum: 0` as a `@Min(0)` bean-validation annotation (consistent with
`CreateRequirementRequest.quantityPerStudent`'s `@Min(1)`), but `InventoryService.setInventory`
also clamps server-side to `[0, requirement.quantityPerStudent]` unconditionally — the contract
calls the upper clamp out explicitly ("owning more than required doesn't get recorded as more"),
and there's no bean-validation annotation that can express an upper bound that depends on another
entity's field, so it has to live in the service regardless.

**Summary reuses `RequirementAssembler`, doesn't recompute `totalDemand`.**
`InventoryService.getSummary`'s `totalRequired` is `RequirementAssembler.toResponses(...)`'s
`totalDemand` field, not a second computation — the two Phase 3/4 "how much is required" numbers
can never drift apart, following the same instinct as `RequirementAssembler`'s own Javadoc about
`totalDemand` being frozen rather than recomputed live.

## Surplus contribution pool (Phase 5) design notes

New surface, all on the existing `PoolController`: `POST
/pools/{poolId}/requirements/{requirementId}/contributions` (offer), `GET
/pools/{poolId}/contributions/mine`, `DELETE /pools/{poolId}/contributions/{contributionId}`
(withdraw), `GET /pools/{poolId}/contributions` (organizer listing), `POST
/pools/{poolId}/contributions/{contributionId}/receive` — delegating to a new
`ContributionService`/`Contribution` entity/`ContributionRepository` over the V1 migration's
already-present `contribution` table. No schema changes were needed to add the surface itself —
see the flagged gap below for the one thing the schema can't back.

**V1 only ever creates `DONATE` rows.** PRD §5.1 marks Lend/Sell as explicitly "later," so
`ContributionService.offer` rejects any other `mode` with 400 — but `ContributionMode` and
`ContributionState` are laid down with their *full* enum sets (matching the migration's check
constraints in full, same instinct as `PoolState`/`RequirementState`), including the Lend
return-path states from PRD §5.4's PM-update, since a later phase advances into them without a
migration.

**Authorization mirrors Phase 4's per-student check, but the pledge is attributed to the parent.**
`offer` uses the identical `MembershipRepository
.findByClassroom_IdAndParentUserIdAndStudent_Id` gate as `InventoryService.setInventory` — the
caller must hold a Membership on the given `studentId` — but the `contribution` table's column is
`offering_parent_id`, not `student_id` (see the flagged gap below), so the row itself only ever
records the caller's own user id; the student is checked and then discarded.

**Withdraw is a parent action, not an organizer one — even though both read the same row.**
`GET /pools/{poolId}/contributions` (organizer) and `DELETE
/pools/{poolId}/contributions/{contributionId}` (offering parent) both resolve a contribution
scoped to the pool via `ContributionRepository.findByIdAndRequirementIdIn` (a
`RequirementRepository.findByIdAndPoolId`-style scoped fetch, one join further out:
contribution -> requirement -> pool), but `withdraw` then checks `offeringParentId == callerUserId`
regardless of the caller's role — the organizer gets 403 there too, per contract ("Caller does not
own this contribution").

**`offeringParentDisplayName` is populated on exactly one endpoint.** Per PRD §5.3's privacy
model ("organizer can see contributor identity ... no public household-level inventory
disclosure"), only `listForOrganizer` looks up display names (batched via
`AppUserRepository.findAllById`, same batch-not-N+1 instinct as `InventoryService.getSummary`).
`offer`, `getMine`, and `markReceived` all pass `null` for it — the field is contract-nullable and
means "not this endpoint's business to say," not "unknown."

**Flagged schema gap — not patched, per this task's boundary.** The contract's `Contribution`
schema declares `studentId`/`studentFirstName` as ordinary (non-nullable) fields, but the V1
migration's `contribution` table has no `student_id` column — only `offering_parent_id` (a parent,
not a specific student). `ContributionResponse.studentId`/`.studentFirstName` are therefore always
`null` on every Phase 5 endpoint, including the immediate `POST .../contributions` response (even
though the request just supplied a `studentId` — it's used for the authorization check and then
never persisted, so it can't be echoed back consistently with what `GET .../mine`/the organizer
listing would show for the same row later). This is a real contract/schema mismatch, flagged here
rather than resolved by unilaterally adding a `student_id` column to the migration or relaxing the
contract's nullability — either fix is a schema/contract decision for separate review, not an
implementation-layer one.

## Allocation & residual-demand engine (Phase 6+7) design notes

New surface, all on the existing `PoolController` (same "pool-scoped, same as
`/pools/{poolId}/confirm`" instinct Phase 4/5 already followed — the file is still under 150 lines
after adding these three routes, so a separate `AllocationController` wasn't warranted yet): `POST
/pools/{poolId}/reconcile`, `GET /pools/{poolId}/allocation` (organizer), `GET
/pools/{poolId}/allocation/mine` (any member) — delegating to a new
`AllocationService`/`AllocationLine`+`ResidualDemandLine` entities over two new tables
(`infra/db/migrations/V3__allocation_and_residual_demand.sql`).

**Design decision — no `OPEN_FOR_CONTRIBUTIONS` hop.** Nothing in this codebase transitions a Pool
from `OPEN_FOR_INVENTORY` to `OPEN_FOR_CONTRIBUTIONS` — Phase 4 (inventory) and Phase 5
(contributions) both already operate freely while a pool is `OPEN_FOR_INVENTORY` (the frontend only
ever gates on `pool.state !== "DRAFT"`), so `OPEN_FOR_CONTRIBUTIONS` has been a dead enum value
since Phase 3 laid down the full state machine. Rather than retrofit an intermediate transition
into already-shipped, already-tested Phase 4/5 gating (a real state-machine change, out of scope
for this task), `AllocationService.reconcile` moves the pool directly `OPEN_FOR_INVENTORY ->
RECONCILING`. `PoolService.transitionToReconciling` is the one place that mutation happens —
package-visible, mirroring how `requireOrganizer`/`requireMembership` already let other services
reach into `PoolService` for the one thing they need without duplicating pool-state logic. This is
flagged explicitly here, the same way the Contribution `studentId` gap is flagged below, rather
than silently worked around.

**Not reusing the V1 migration's `allocation` table.** V1 speculatively laid down an `allocation`
table ("Phases 3-11 tables ... no API endpoints in this pass") shaped as one `fulfillment_type` +
`quantity` pair per row. That can't back the contract's `AllocationLine` schema, which needs the
owned/pool/purchase breakdown as three separate integer columns on the same line (so a caller can
see, e.g., that a line is `PURCHASE_REQUIRED` *and* exactly how much of the shortfall the pool
already covered). Widening or renaming that table is a schema decision for separate review, same
boundary as the Contribution `studentId` gap — V3 instead adds `allocation_line` and
`residual_demand_line` as new tables and leaves the V1 `allocation` table untouched and unused.

**The algorithm, run once, frozen forever (V1 has no re-reconcile).** `AllocationService.reconcile`
is one `@Transactional` method implementing PRD §6's waterfall per `(requirement, student)` pair,
in the exact order the contract's `AllocationStatus` doc describes: household-owned inventory first
(Phase 4's `ParentInventory.ownedQuantity`), then the pool's `RECEIVED` surplus contributions
(Phase 5's `Contribution` — `PLEDGED` rows are explicitly excluded from `poolAvailable`, per PRD
§5.4/§6.1: a promise isn't physical supply yet), then whatever's left is `purchaseRequiredQuantity`.
Requires the pool to currently be `OPEN_FOR_INVENTORY` — 409 otherwise (`DRAFT`, or already past
`OPEN_FOR_INVENTORY`) — and there is no path back to `OPEN_FOR_INVENTORY`, so once reconciled a pool
can never be reconciled again in V1; this matches `PoolService.confirm`'s identical
one-time-transition precedent (see the Phase 3 notes above), and the frozen `AllocationLine`/
`ResidualDemandLine` rows are read back verbatim by both GET endpoints afterward, never
recomputed.

**Scarce pool supply is allocated first-joined-first-served — a tie-break, not a fairness
ranking.** Per the contract's own wording, students are processed in `Membership.createdAt`
ascending order on the classroom, and `poolAvailable` is a single running total per requirement
that only decreases as earlier-joined students draw from it — so if the pool can't cover everyone,
whoever joined earliest gets served first and later joiners are more likely to land in
`PURCHASE_REQUIRED`. `AllocationServiceTest.reconcile_allocatesScarcePoolSupplyInJoinOrder_...`
exercises this directly with two students and pool supply sized for exactly one of them.

**Membership dedup by student, not by row.** A student could in principle have two Membership rows
on the same classroom (e.g. two co-parents who each independently joined with the same child) —
the `allocation_line` table's unique constraint is `(requirement_id, student_id)`, so exactly one
line per student is required regardless of how many Membership rows reference them.
`AllocationService` dedupes the join-ordered Membership list by student id, keeping the
earliest-`createdAt` row per student — the same "distinct student" instinct
`MembershipRepository.countDistinctStudentsByClassroom_Id` already uses, which is what
`Pool.confirmedStudentCount` itself is built on.

**`totalRequired` is derived from students actually processed at reconcile time, not the frozen
`confirmedStudentCount`.** Unlike `Requirement.totalDemand` (frozen once, at confirm), nothing
stops a family from joining the classroom during `OPEN_FOR_INVENTORY` (see the dead
`OPEN_FOR_CONTRIBUTIONS` note above — Phase 4/5 already allow this), so a late joiner can appear
between confirm and reconcile. `AllocationService.reconcile` re-reads classroom Membership live and
gives that late joiner their own `AllocationLine` rows too — the contract's algorithm description
says "every Membership ... with a non-null student_id" at reconcile time, not "every Membership as
of confirm". Each requirement's `ResidualDemandLine.totalRequired` is therefore computed as
`quantityPerStudent × (number of students actually processed for that requirement)` rather than
`Pool.confirmedStudentCount` — this keeps the `totalRequired = totalOwned + totalPoolFulfilled +
residualDemand` accounting identity exactly true by construction. In the common case (no late joins
between confirm and reconcile) this is numerically identical to `Requirement.totalDemand`, matching
the contract's own description of the field ("Same as Requirement.totalDemand"); flagged here as
the one edge case where the two numbers could in principle diverge.

**Response assembly reuses in-memory data from the reconcile pass itself where possible.**
`reconcile`'s own response is built straight from the `Requirement`/`Membership` objects already
loaded during the algorithm (no extra queries); the two GET endpoints, reading the persisted
snapshot back on a later request, batch-fetch requirement names and student first names via
`RequirementRepository`/`StudentRepository.findAllById` — the same batch-not-N+1 instinct as
`ContributionService.listForOrganizer`'s `AppUserRepository.findAllById` call.

**Authorization mirrors the existing organizer/member split exactly.** `reconcile` and
`getAllocationForOrganizer` both use `PoolService.requireOrganizer`; `getMyAllocation` uses
`PoolService.requireMembership` and then derives "own students" from the caller's own Membership
rows, the same pattern `InventoryService.getMyInventory` already established. `getMyAllocation`
returns an empty list (200, not an error) if reconcile hasn't run yet — the same "nothing to show
yet" precedent `InventoryService.getMyInventory` sets for a still-`DRAFT` pool — while
`getAllocationForOrganizer` 409s in that case instead, per the contract.

## Bulk pack optimizer (Phase 8) design notes

New surface, split into its own `PurchasePlanController` rather than folded into `PoolController`
(the latter was already 148 lines before these six routes — the same size-driven judgment call the
Phase 6/7 agent flagged as theirs to make before adding three more routes there): `POST
/pools/{poolId}/requirements/{requirementId}/product-offers`, `GET /pools/{poolId}/product-offers`,
`DELETE /pools/{poolId}/product-offers/{offerId}`, `POST /pools/{poolId}/purchase-plan/generate`,
`GET /pools/{poolId}/purchase-plan`, `POST /pools/{poolId}/purchase-plan/approve` — delegating to a
new `PurchasePlanService`/`ProductOffer`+`PurchasePlan`+`PurchasePlanLine` entities over the V1
migration's already-present `product_offer`/`purchase_plan`/`purchase_plan_line` tables. **No new
migration was needed** — unlike Phase 6/7's `allocation` table (schema-incompatible with the
contract's `AllocationLine` shape, and replaced with a fresh V3 migration), these three tables
already have exactly the columns the contract's `ProductOffer`/`PurchasePlan`/`PurchasePlanLine`
schemas need, so the JPA entities map onto them directly.

**The optimizer (PRD §7.1) is a separated, independently-unit-tested class, `PackOptimizer`.**
Package-private in `service/`, with no Spring/HTTP/persistence dependency at all — just a static
`optimize(need, offers)` method — so `PackOptimizerTest` can exercise the DP directly. It's the
classic unbounded-knapsack "minimum cost to cover at least N units": `dp[0..need+maxPackQuantity]`,
one relaxation pass per offer, with a parent-pointer array for backtracking. The PRD's own worked
example (need=320 pencils; 24-pack@499c, 48-pack@849c, 144-pack@1899c) is asserted numerically in
`PackOptimizerTest.optimize_pencilExample_matchesPrdWorkedAnswerExactly`: the DP finds 2×144-pack +
1×48-pack = 336 units for 4647 cents, waste 16 — independently verified by hand in that test's
Javadoc (comparing against 3×144-pack alone, 2×144+2×24, and 1×144+4×48, all of which cost more),
not just trusted because the code produced a number.

**The least-waste tie-break falls out of the scan order, no separate pass needed.** The contract
says "minimize cost first, then fewest total units purchased." Scanning `q` from `need` to
`need+maxPackQuantity` ascending and only overwriting the running-best `(cost, q)` pair on a
*strict* cost improvement means the smallest `q` achieving the minimum cost is kept automatically —
ties at a higher `q` never overwrite it. `PackOptimizerTest`'s tie-break test (two offers, same
cost, one with zero waste and one with one unit of waste) exercises this directly.

**Waste attribution when a requirement's plan spans multiple offers.** The DP's backtracking can
legitimately use more than one distinct offer for the same requirement (the PRD's own worked
example does — 144-pack and 48-pack together). `PackOptimizer` returns its chosen offers sorted by
`offerId` for exactly one reason: `PurchasePlanService.generate` always attributes the requirement's
*entire* waste number to the first (smallest-`offerId`) `PurchasePlanLine`, zero on every other line
for that requirement — a stable, deterministic "which line" answer with no dependence on map/HashSet
iteration order. This is an explicit judgment call, since the contract only says the whole number
goes on "a single designated line," not which one — sorting by `offerId` was picked because it's
the only ordering available that has nothing to do with business meaning (unlike, say, "the
cheapest line" or "the largest line," either of which would read as an implied policy this phase
isn't actually making).

**Why shipping isn't in the optimizer's cost function yet.** `ProductOffer.shippingCents` is
mapped, validated (`@Min(0)`, defaults to 0 when omitted per the contract), and returned in every
response — but `PackOptimizer.optimize` compares offers on `priceCents` alone. Folding shipping
into the cost function is genuinely ambiguous without more product decisions this phase doesn't
make: is shipping per-pack, per-order, or amortized once across every line for a requirement (or
across the whole plan)? The task description calls this out explicitly as V1-scoped-out, in the
same spirit as the contract not yet reasoning about tax/fees either — flagged here rather than
guessed at.

**Validate-before-DP, and name every missing requirement at once.** `generate` reads back Phase
6/7's frozen `ResidualDemandLine` snapshot (never recomputed), filters to `residualDemand > 0`, and
checks *every one* of those requirements has at least one `ProductOffer` before running the
optimizer on any of them — collecting every requirement name still missing an offer into one 409
message, rather than 409ing on the first miss and making the organizer fix them one at a time. A
requirement with `residualDemand == 0` never triggers this check at all and is skipped before ever
querying its offers — `PurchasePlanServiceTest.generate_skipsRequirementsWithZeroResidualDemand_evenWithNoOffers`
proves the skip happens even when such a requirement has zero candidate offers, which would
otherwise 409 if the filter were missing.

**A zero-residual-demand pool still gets a (trivial) plan.** If every requirement's residual demand
is already 0 at generate time (fully covered by household inventory + pool contributions), nothing
in the contract says this should be an error — `generate` still creates a `PurchasePlan` with zero
`PurchasePlanLine` rows and `totalCostCents = 0`, and still moves the pool `RECONCILING ->
PURCHASE_PROPOSED`. This wasn't explicitly specified either way; flagged here as the judgment call
rather than silently rejecting an all-fulfilled pool.

**`approve` deliberately never touches `Pool.state`.** Per the contract's own summary ("Does not
itself move the Pool's own state — billing/payment (Phase 9) owns the next pool-state transition
once an approved plan exists"), `PurchasePlanService.approve` only calls `PurchasePlan.approve()`
(`PROPOSED -> APPROVED`, sets `approvedAt`) — `PurchasePlanServiceTest
.approve_transitionsProposedToApproved_withoutTouchingPoolState` asserts `poolRepository` is never
even saved during approval, so this boundary can't silently regress once Phase 9 exists and starts
reaching for `PoolService`'s package-visible transition methods.

**One plan per pool, enforced in the service, not the schema.** The V1 migration's `purchase_plan`
table has no unique constraint on `pool_id` (unlike, say, `residual_demand_line`'s per-requirement
uniqueness in the V3 migration) — `PurchasePlanRepository.existsByPoolId`/`findByPoolId` are how
`generate`/`getPurchasePlan`/`approve` all enforce "at most one plan per pool" themselves. This
matches the "schema changes are reviewed separately" boundary the Phase 5 Contribution `studentId`
gap and the Phase 6/7 `allocation` table both already established — widening the migration to add
the constraint is a fine follow-up, not made unilaterally here since the service-level check is
already sufficient for V1's correctness bar (no concurrent-request race is exercised or claimed to
be closed by this).

**Authorization is organizer-only across the entire surface** — `addProductOffer`,
`listProductOffers`, `removeProductOffer`, `generate`, `getPurchasePlan`, and `approve` all use
`PoolService.requireOrganizer`, matching the contract's uniform "Caller is not an organizer" 403 on
every one of these six endpoints (unlike Phase 6/7's allocation surface, which has both an
organizer-only and a member-facing "mine" view — this phase has no member-facing counterpart at
all, per the contract).

## Package layout

```
app.classpool.api
├── domain/       JPA entities — Phase 1-2 (AppUser, Household, Student, School, SchoolYear,
│                 Classroom, Membership, Invite), Phase 3 (Pool, Requirement), Phase 4
│                 (ParentInventory), Phase 5 (Contribution), Phase 6/7 (AllocationLine,
│                 ResidualDemandLine), and Phase 8 (ProductOffer, PurchasePlan, PurchasePlanLine)
│                 tables, plus enums
├── repository/   Spring Data JPA repositories, including the native pg_trgm dedup queries and the
│                 shared organizer-role / confirmed-student-count / join-order queries on
│                 MembershipRepository
├── service/      Business logic — auth, schools, classrooms, invites, household dashboard, pools
│                 + requirements, household inventory, surplus contributions, the allocation &
│                 residual-demand engine, the bulk-pack optimizer (PurchasePlanService +
│                 PackOptimizer), the Redis-backed session/magic-link stores, email boundary
├── security/     Cookie-based session auth filter + cookie read/write helper
├── config/       SecurityConfig (filter chain, public-endpoint allowlist), OAuth2Config
├── dto/          Request/response records matching contracts/openapi.yaml's schemas exactly
├── web/          @RestController classes, one per resource group in the OpenAPI paths
└── exception/    ApiException hierarchy (400/401/403/404/409) + a @RestControllerAdvice
```
