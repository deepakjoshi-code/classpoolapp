# ClassPool — Web (PWA)

Next.js 15 (App Router) + TypeScript PWA for ClassPool's parent/organizer-facing
frontend. Covers Phase 1 (PWA shell + auth), Phase 2 (schools/classes/
memberships), Phase 3 (pools + manual requirement ingestion/verification),
Phase 4 (household inventory — "Shop Your Home First"), and Phase 5
(contribution pool — offer/withdraw surplus, organizer receive confirmation)
of the V1 build order — see `../../ARCHITECTURE.md` §4 and
`../../docs/PRD.md` §17.3.

## Running it

```bash
npm install
npm run dev
```

Opens on http://localhost:3000.

This app expects `apps/api` (the Spring Boot backend) to be running and
reachable, per `contracts/openapi.yaml`'s relative server URL (`/api/v1`).
Two ways to wire that up locally:

- **Same-origin reverse proxy** (matches the contract literally): put both
  apps behind one host (e.g. a dev proxy, or Next's own `rewrites()`) so
  `/api/v1/*` on `localhost:3000` forwards to the API.
- **Cross-port dev** (simplest if you're just running both apps side by
  side): set `NEXT_PUBLIC_API_BASE_URL` to the API's origin, e.g.

  ```bash
  NEXT_PUBLIC_API_BASE_URL=http://localhost:8080/api/v1 npm run dev
  ```

  (see `src/lib/api/client.ts`).

Sessions are a cookie (`CLASSPOOL_SESSION`) set by the API — the frontend
never touches raw tokens, it just calls `fetch` with `credentials: "include"`.

## Testing

```bash
npm test          # vitest run (component tests)
npm run typecheck # tsc --noEmit
npm run build     # production build (also type-checks + lints via Next)
```

Component tests live in `src/tests/` (Vitest + React Testing Library), per
`../../ARCHITECTURE.md`'s testing strategy. At minimum:

- `CreateClassroomForm.test.tsx` — renders the create-class form, submits it,
  and covers both the plain-success path and the `dedupWarning` path (PRD
  §2.3's "Is this your class?" prompt) — including that the warning is
  actually rendered and actionable, not silently discarded.
- `JoinForm.test.tsx` — the join-via-invite step: student-name form → submit
  → calls `POST /invites/{token}/join`, plus a failure path.
- `RequirementForm.test.tsx` — the manual add/edit requirement form (PRD
  §3.3's Correct/Edit actions): renders all fields, asserts the strictness
  `<select>` shows the three modes in plain language (not the raw
  `EXACT`/`EQUIVALENT_ALLOWED`/`GENERIC` enum values), submits an add via
  `POST`, edits via `PATCH` pre-filled with the existing values, and shows a
  specific message on the pool-no-longer-DRAFT 409.
- `ConfirmPoolAction.test.tsx` — the one-way confirm action: asserts the
  actual `POST /pools/{id}/confirm` call is unreachable without first passing
  through an explicit "this can't be undone" step, that the button is
  disabled with zero requirements, and that both 409 cases (zero
  requirements, already confirmed) render distinct, specific messages.
- `InventoryStepperRow.test.tsx` — the "Shop Your Home First" +/- stepper
  (PRD §4.2): renders the item name/needed quantity/current count, asserts
  the stepper buttons carry real accessible names ("Decrease/Increase owned
  <item> for <student>", not bare icon buttons), that a click updates the
  count optimistically before the debounced `PUT .../inventory` call lands
  with the right `studentId`/`ownedQuantity`, that rapid clicks collapse into
  one network call carrying the final value, that the buttons — not just the
  displayed number — are disabled at 0 and at `quantityPerStudent` so a user
  can't spam the count out of range even before the server's own clamp, and
  that a failed save reverts the count and surfaces an inline error.
- `InventorySummaryPanel.test.tsx` — the organizer aggregate view: fetches
  `GET /pools/{id}/inventory/summary` and renders the "completed X/Y
  students" line plus each requirement's already-owned total.
- `ContributionOfferCard.test.tsx` — the "Offer surplus" pledge card (PRD
  §5.1): renders the requirement/quantity context and an offer form, submits
  `POST .../contributions` with the exact `{ studentId, quantity, mode:
  "DONATE" }` payload, disables submit until a valid quantity is entered,
  shows this household's own pledge(s) with `PLEDGED`/`RECEIVED` status text
  worded to read unambiguously different (not just a color swap — WCAG 2.1
  AA 1.4.1), asserts a withdraw button appears only for a still-`PLEDGED`
  pledge (never for `RECEIVED`) and that clicking it calls `DELETE
  .../contributions/{id}`, surfaces the 409 "already received" message, and
  — the privacy check — asserts the card never renders "From ..." or any
  household-identifying text even when `offeringParentDisplayName` is present
  on the object it's handed (it's the wrong component to ever show that).
- `OrganizerContributionsPanel.test.tsx` — the organizer confirmation view
  (PRD §12.3 "View unreceived contributions"): fetches `GET
  /pools/{id}/contributions` and renders each offering household's name,
  shows "Mark received" only on `PLEDGED` rows and calls `POST
  .../{id}/receive` correctly, and a fallback message on a 403. Also
  contains an explicit **visibility** test suite that renders the real
  `PoolDetailPage` end-to-end (mocking `next/navigation` and
  `useCurrentUser`) once as a plain parent and once as an organizer on the
  same pool: for the parent, it asserts the panel's heading, any contributor
  name, and the "Mark received" button are all absent from the DOM, *and*
  that `GET /pools/{id}/contributions` — the identity-carrying endpoint
  itself — is never called; for the organizer, it asserts the same panel and
  data ARE rendered, so the parent-side assertion is proven to be a real
  role gate and not just an always-off component.

## API client — generated from the contract

`contracts/openapi.yaml` is the source of truth. Nothing in this app
hand-writes a response/request interface that duplicates what the contract
already defines.

```bash
npm run generate:api
```

runs `openapi-typescript` against `../../contracts/openapi.yaml` and writes
`src/lib/api/generated/types.ts`. That output **is committed** — the app
builds without requiring this step on every install — but re-run it whenever
the contract changes.

- `src/lib/api/client.ts` — a thin `openapi-fetch` client typed against the
  generated `paths`. This is the only place that should call `fetch()`
  against the API.
- `src/lib/api/types.ts` — convenience re-exports of the generated component
  schemas, plus one narrowing utility (`DeepRequired`) applied to response
  types only. **Why:** none of the response schemas in `openapi.yaml`
  declare a `required:` list, so every field openapi-typescript generates
  for them is technically optional — even fields like `Classroom.id` that
  are obviously always present. Rather than hand-writing parallel "the real
  shape" interfaces (the exact duplication/drift risk generating from the
  contract is meant to avoid) or editing the shared contract unilaterally,
  this narrows once, in our own code, with the assumption documented inline.
  Flagged for the API side as a possible contract polish item.

## PWA implementation

Hand-rolled, not `next-pwa`:

- `public/manifest.json` — name/short_name/start_url/`display: standalone`/
  icons/theme, per PRD §11.2.
- `public/icons/*.png`, `public/apple-touch-icon.png` — placeholder icons
  (solid brand-blue background + "CP" monogram), generated with Pillow.
  Swap these for real artwork before shipping.
- `public/sw.js` — a small, readable service worker: precaches the app
  shell, network-first for navigations (falling back to cache, then to
  `public/offline.html`), cache-first for same-origin static assets, and
  **never** touches anything under `/api/` (no cached session state, no
  offline payments — PRD §11.4 explicitly rules that out). Registered by
  `src/components/ServiceWorkerRegister.tsx` in the root layout.
- `src/app/layout.tsx` — manifest link, `apple-web-app` meta tags for iOS
  home-screen installability, theme-color viewport meta.

Why hand-rolled instead of `next-pwa`: `next-pwa` wraps Workbox and adds a
build-time dependency whose config surface (runtime caching strategies,
Webpack plugin wiring) is bigger than this phase needs. A ~90-line service
worker covering exactly the three PRD §11.4 behaviors (cache app shell, show
last-cached state offline, never cache `/api/`) is easier for the next
engineer to read end-to-end than to configure through a plugin. Revisit if
later phases need more sophisticated offline queuing (§11.4's "queue simple
local inventory edits" — not yet built, out of scope for Phase 1-2).

## Pages built this pass

| Route | Purpose |
|---|---|
| `/sign-in` | Google button + email magic-link form, no password (PRD §2.2) |
| `/auth/verify` | Exchanges a magic-link token (`?token=`) for a session |
| `/` | Household dashboard — every classroom as its own card (PRD §12 multi-class update) |
| `/classrooms/new` | Create-a-class flow: school search/dedup → grade/teacher/year/count → handles `dedupWarning` |
| `/classrooms/[id]/invite` | Join link + QR + one-tap share, shown right after creation |
| `/join/[token]` | Public, pre-auth invite landing page → sign-in → student-name join step |
| `/classrooms/[id]/pools/new` | Organizer/co-organizer only: name a pool ("Fall Supplies") and start it in `DRAFT` |
| `/pools/[id]` | Pool detail — requirement list, add/edit/remove + confirm for an organizer while `DRAFT`, read-only otherwise (Phase 3). Once the pool is past `DRAFT`, also links to `/pools/[id]/inventory` for every member and shows the organizer inventory summary panel (Phase 4) |
| `/pools/[id]/inventory` | "Shop Your Home First" (PRD §4) — the caller's own household inventory checklist: one +/- stepper row per (requirement, student) they have in this classroom (Phase 4) |
| `/pools/[id]/contribute` | "Offer surplus" (PRD §5.1) — the caller's own pledge screen: one offer card per (requirement, student) they have in this classroom, showing their own pledge status and a withdraw action while still `PLEDGED`. Linked from `/pools/[id]` once the pool is past `DRAFT`, alongside (but visually secondary to, per the "optional/low-pressure" framing) the inventory link (Phase 5). The organizer's confirmation view is not a page — it's `OrganizerContributionsPanel`, embedded directly on `/pools/[id]` next to the inventory summary, same as Phase 4 |

## Known discrepancies / assumptions against the contract

Flagged here rather than editing `contracts/openapi.yaml` unilaterally:

1. **Google OAuth initiation has no documented endpoint.** The contract
   documents `GET /auth/google/callback` (the OAuth2 redirect target) but not
   where the "Continue with Google" button should link to. Spring Security
   OAuth2 Client (per `ARCHITECTURE.md` §2) serves that redirect by
   convention at `/oauth2/authorization/{registrationId}`, not as a
   documented JSON operation, so `SignInForm` links there
   (`${API_BASE_URL}/oauth2/authorization/google`). If the backend uses a
   different path, this is a one-line change.
2. **The magic-link email's destination URL is assumed.** The contract only
   defines the token-exchange endpoint (`GET /auth/magic-link/verify`), not
   what URL the emailed link itself points to. `/auth/verify` in this app is
   built as that destination (reads `?token=`, calls the exchange endpoint,
   redirects). If the email is templated to point elsewhere, update that
   route accordingly.
3. **`createClassroom`'s dedup check runs *after* creation, not before.**
   The PRD narrative (§2.3) describes fuzzy-matching prompting the organizer
   *before* a new class is created ("creation is still self-serve, just with
   a dedup check in front of it"). The contract's actual behavior (per its
   own description on `POST /classrooms`) is that the classroom is created
   unconditionally and `dedupWarning` comes back informationally alongside
   it. There's also no endpoint for an organizer to undo that creation and
   join one of the flagged classrooms directly by ID — only
   `POST /invites/{token}/join` exists, which needs an invite token. The UI
   (`DedupWarning.tsx`) is built against the contract as written: it shows
   the warning prominently and never silently discards it, but "yes, this is
   mine" can only point the organizer at pasting an invite link/code from
   that class's real organizer, not perform a merge. Worth a follow-up
   contract conversation with the API side — either a pre-creation
   `POST /classrooms/check-dedup`-style endpoint, or a way to join/merge
   directly from the warning.
4. **Response schemas' missing `required:` lists** — see the `DeepRequired`
   note under "API client" above.
5. **No mobile 5-tab nav (`HOME | POOL | SHARE | ORDERS | PROFILE`, PRD §12)
   yet.** Phase 3 adds the POOL destination (`/pools/[id]`) but ORDERS still
   has no endpoints in the contract, so a full persistent tab bar still seems
   premature — add it once ORDERS lands too.
6. **Strictness plain-language copy is our own interpretation.** PRD §3.3
   only names the three modes tersely ("Exact item / Equivalent allowed /
   Generic") — it doesn't give parent-facing copy for each. The label/hint
   text in `src/lib/pool-labels.ts` ("Must match exactly" / "Any equivalent
   brand or type is fine" / "Any item that fits the description works") is
   this app's best-faith reading of what each mode should mean to a
   non-technical parent, not text pulled from the PRD verbatim. Worth
   confirming with product/copy before ship.
7. **One "active" pool per classroom, by convention, not by contract.**
   `Classroom.pools` can hold many pools (PRD §2.3: "a class can run
   multiple pools per year"), but the household dashboard's `ClassroomCard`
   only surfaces `pools[0]` (most recent, per the contract's ordering) as
   "the" pool for that card, per the task's explicit guidance not to
   over-build multi-pool-per-classroom UI for Phase 3. A classroom with two
   simultaneously-active pools would only show one from the dashboard (the
   other is still reachable by URL) — revisit if that becomes a real case.
8. **Household inventory "completion" is defined as coverage, not as a
   touched/untouched flag (Phase 4).** `InventoryLine` has no field telling
   the client whether a row's `ownedQuantity` is a value the household
   actually entered vs. a server-side default of 0 for a requirement they
   never opened — both look identical on `GET .../inventory`. Rather than
   fake that distinction with client-only "have I clicked this row this
   session" state (which would forget itself on refresh and show nothing to
   a returning household who already finished last time), `/pools/[id]/
   inventory`'s progress message (`inventoryCoverageMessage` in
   `pool-labels.ts`) is driven by how many rows are already fully covered
   (`stillNeeded === 0`) out of the total — "X of Y items already covered",
   escalating to "You already have all Y items covered!" at 100%. This is
   honest immediate value from the moment the page loads (PRD §4.2's "show
   immediate value... 'You already have $31 worth of your list'" — adapted
   to an item count since this phase has no item prices to total up), and it
   updates live as the household adjusts a stepper, but it does mean a
   household that genuinely owns zero of everything looks identical to one
   that hasn't started yet (both read "0 of Y covered"). Worth revisiting if
   the API ever adds an explicit "has this row been set" flag.
9. **No client-side role check gates viewing a pool, only managing one.**
   `GET /pools/{poolId}` requires *some* Membership on the classroom (any
   role), so any parent in the class can view a pool read-only — matching
   the contract. `/pools/[id]` and `/classrooms/[id]/pools/new` check the
   caller's own `Membership.role` (from `GET /me`) client-side to decide
   whether to show management controls / allow pool creation, but this is a
   UX nicety, not a security boundary — the API's 403s are the real
   enforcement, same pattern as the rest of this app (PRD §14's tenant
   isolation is a backend concern).
10. **"Offer surplus" derives the caller's own (requirement × student) pairs
    from `GET /me`'s `memberships`, not from a contribution-specific listing
    endpoint (Phase 5).** Unlike inventory, where `GET /pools/{id}/inventory`
    hands back one row per (requirement, student) the caller already has
    standing to act on, there's no equivalent "here's what you're allowed to
    offer against" endpoint for contributions — `POST .../contributions`
    only takes a `studentId` in its body and 403s if the caller has no
    Membership on that student for this classroom. So `/pools/[id]/contribute`
    builds the student list itself, filtering `auth.user.memberships` (from
    the already-fetched `GET /me`) down to this pool's `classroomId`, then
    crosses that with every requirement — same per-student authorization
    boundary the contract describes, just assembled client-side from data we
    already had rather than a new round trip. Worth a follow-up contract
    conversation if a future phase wants a single endpoint that returns this
    directly (mirroring `InventoryLine`).
11. **Privacy (PRD §5.3) is enforced by which component/endpoint a screen
    uses, not by a field-level redaction step (Phase 5).** There are two
    completely separate read paths for contributions — a parent's own
    (`GET .../contributions/mine`, rendered only by `ContributionOfferCard`,
    which never reads or renders `offeringParentDisplayName`) and the
    organizer's (`GET .../contributions`, rendered only by
    `OrganizerContributionsPanel`, embedded on `/pools/[id]` exclusively
    inside the same `isOrganizer` branch that already gates
    `InventorySummaryPanel`). No component tries to be both views with a
    prop toggling whether names show — that would leave one bug away from a
    parent seeing another household's name. `OrganizerContributionsPanel.
    test.tsx` includes an explicit end-to-end check of this: it renders the
    real `PoolDetailPage` as a plain parent and asserts the identity-carrying
    endpoint is never even called, then re-renders it as an organizer on the
    same pool and asserts the panel does appear — proving the gate is a real
    role check, not a component that just never renders.
