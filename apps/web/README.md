# ClassPool — Web (PWA)

Next.js 15 (App Router) + TypeScript PWA for ClassPool's parent/organizer-facing
frontend. Covers Phase 1 (PWA shell + auth), Phase 2 (schools/classes/
memberships), Phase 3 (pools + manual requirement ingestion/verification),
Phase 4 (household inventory — "Shop Your Home First"), Phase 5
(contribution pool — offer/withdraw surplus, organizer receive confirmation),
Phase 6 (allocation & residual demand — organizer "work out what still
needs buying" action, organizer purchase breakdown, household's own status
view), Phase 7+8 (product-offer entry and the bulk-pack purchase plan —
organizer candidate price options per item, "work out the cheapest way to
buy what's left" action, the generated plan with its running total and an
approve step), Phase 9 (Stripe Connect payment collection — organizer
bank-account onboarding, generating each household's payment, a household's
own pay screen, cash fallback + refund, and the 90%-threshold finalize gate),
Phase 10 (ordering & distribution — organizer records the actual order
against the approved plan with optional per-line substitution editing,
organizer sets up and prints per-household pick lists and tracks delivery,
a household's own "what you're receiving" view, the class reserve ledger,
and the final "close out this pool" step), Phase 11 (AI-assisted
requirement import — organizer pastes a forwarded email/portal text/message
and gets candidate requirements back with confidence + source evidence,
alongside the permanent manual-entry path), and the final phase (a site-wide
notification bell — PRD §11.3's event list, `NotificationBell` mounted once
in the new `SiteHeader` — and a shareable savings-summary card on the pool
detail page — PRD §16.3's viral-loop artifact, `SavingsSummaryCard`, visible
to any pool member once reconciled) of the V1 build order — see
`../../ARCHITECTURE.md` §4 and `../../docs/PRD.md` §17.3.

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
- `ImportRequirementsForm.test.tsx` — the AI-assisted pasted-text import form
  (PRD §3.1/§3.2, Phase 11): asserts the source-type `<select>` shows the
  three plain-language options (not the raw `PASTED_EMAIL`/`PASTED_PORTAL`/
  `PASTED_MESSAGE` values), submits `POST .../requirement-sources` with the
  exact `{ sourceType, rawText }` body, shows a results summary that counts
  ready-to-review (`EXTRACTED`) and needs-a-closer-look (`NEEDS_REVIEW`)
  items as distinct, worded-differently sentences (not one blended count),
  calls `onImported` with the full batch, keeps submit disabled with no
  pasted text, and shows distinct messages for the pool-no-longer-DRAFT 409
  vs. a generic failure (the latter never calling `onImported`).
- `RequirementListItem.test.tsx` — the requirement row's AI-provenance
  display (Phase 11): a manual entry (`confidence: null`) renders no
  confidence badge, no "AI-extracted"/"needs a closer look" text, and no
  source-evidence disclosure at all; an `EXTRACTED` item and a
  `NEEDS_REVIEW` item both show their confidence as a percentage but with
  visibly/textually distinct wording ("AI-extracted — N% confidence" vs.
  "Needs a closer look — N% confidence"); `sourceEvidence` is present in the
  rendered output (inside an expandable disclosure) for either; and the
  organizer's existing edit/remove controls still work alongside the new
  provenance UI.
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
- `ReconcileAction.test.tsx` — the organizer's one-way "work out what still
  needs buying" action (PRD §6): asserts `POST /pools/{id}/reconcile` is
  unreachable without first passing through the "this can't be undone" step,
  that cancelling that step backs out without calling the API, that success
  calls `onReconciled` with the returned `AllocationSummary`, and that the
  already-reconciled 409 and a generic failure render distinct messages.
- `OrganizerAllocationPanel.test.tsx` — the organizer's purchase-breakdown
  view: fetches `GET /pools/{id}/allocation` and renders each requirement's
  residual-demand line ("N still need(s) to be purchased", or "Fully
  covered!" at zero) plus its per-student breakdown using
  `allocationStatusLabel`, asserts no raw `AllocationStatus` enum value is
  ever rendered, and handles the not-yet-reconciled 409 with a message
  instead of crashing (reachable only via a stale page state, since the pool
  detail page's own gating should prevent it in normal use).
- `MyAllocationPanel.test.tsx` — the household's own "where things stand"
  view: fetches `GET /pools/{id}/allocation/mine` and renders only the
  plain-language status per (requirement, student) — never a raw enum
  value — and treats an empty array (reconcile hasn't run yet) as a normal
  empty state, not an error.
- `money.test.ts` — `formatCents`/`dollarsToCents` (PRD §7.3/§8): integer
  cents format as a dollar string (`4647` -> `"$46.47"`, never raw cents or
  an unrounded float), a typed dollar string parses back to the same integer
  cents (rounding away IEEE-754 float drift, e.g. `"4.99"` -> `499`, not
  `498`), a round trip through both directions lands on the same cents, and
  unparseable input returns `NaN` rather than silently defaulting to 0.
- `ProductOfferForm.test.tsx` — the organizer's "add a price option" form
  (PRD §7.3): renders already-added offers with cents formatted as dollars
  and a remove action, submits an add converting the typed dollar price (and
  optional shipping) to integer cents in the exact `POST` body shape, keeps
  submit disabled until retailer/pack size/price are all filled in, and
  shows distinct messages for the pool-no-longer-RECONCILING 409 on add and
  the plan-already-generated 409 on remove.
- `OrganizerAllocationPanel.test.tsx` also covers the embedded offer forms
  (added on top of this component in Phase 7): while `poolState` is
  `"RECONCILING"`, the "add a price option" form (and its already-added
  offers) appears only under a requirement that still has residual demand,
  disappears once the pool has moved past `RECONCILING` (and the
  product-offers endpoint isn't even called in that case), and adding an
  offer through the embedded form updates what's shown without a full
  reload.
- `GeneratePurchasePlanAction.test.tsx` — the organizer's one-way "work out
  the cheapest way to buy what's left" action (PRD §7.1's bulk-pack
  optimizer, described in plain language): asserts `POST
  /pools/{id}/purchase-plan/generate` is unreachable without first passing
  through the "this can't be undone" step, that cancelling backs out without
  calling the API, and that the missing-price-option 409 and a generic
  failure render distinct messages (never the wrong one for the wrong case).
- `PurchasePlanPanel.test.tsx` — the generated purchase plan (PRD §7-8):
  fetches `GET /pools/{id}/purchase-plan` and renders each line's
  retailer/pack/quantity/cost and a running grand total, all formatted as
  dollars (never raw cents), the plan's own PROPOSED/APPROVED state in
  plain language (never the raw enum), the not-yet-generated 409 handled
  gracefully, and the approve action's explicit-confirm-then-success path
  updating local state (to `APPROVED`) without a full reload.
- `StripeOnboardingCard.test.tsx` — organizer Stripe onboarding (PRD §8.4):
  a not-started 404 shows the connect CTA and starting it calls `POST
  .../stripe-onboarding`; from `PENDING`, "Simulate returning from Stripe"
  calls `POST .../stripe-onboarding/complete` and flips to the confirmed
  `ACTIVE` state; an already-`ACTIVE` account renders the confirmed state
  directly with no onboarding buttons and no POST calls.
- `GeneratePaymentsAction.test.tsx` — the organizer's one-way "open payment
  for this pool" action (PRD §8.1-8.3): asserts a specific, named reason is
  shown instead of a dead button when the purchase plan isn't `APPROVED` yet
  or Stripe isn't `ACTIVE` yet (including a never-started 404), that `POST
  /pools/{id}/payments/generate` is unreachable without first passing
  through the "this can't be undone" step once both preconditions are met,
  and that cancelling that step backs out without calling the API.
- `OrganizerPaymentsPanel.test.tsx` — the organizer's per-household payment
  list (PRD §8.4): fetches `GET /pools/{id}/payments` and renders each
  household's identity/amount (as dollars)/plain-language state, shows "Mark
  cash pending" only on a `PENDING` row and "Mark cash received" only once
  it's `PENDING_CASH` (never both), shows a refund button only for
  `PAID`/`PAID_CASH_RECEIVED` rows (never `PENDING`/`REFUNDED`/etc.) and
  never once the pool has reached `ORDERED`, and a fallback message on a
  403.
- `PaymentsThresholdPanel.test.tsx` — the organizer's payment-threshold view
  and finalize action (PRD §8.4 update): renders percent collected and the
  outstanding-households risk banner only when below the 90% threshold;
  below threshold, asserts the finalize confirm button stays disabled until
  an explicit checkbox is ticked and then sends `acknowledgeBelowThreshold:
  true`; at/above threshold, asserts a normal one-step confirm with no
  checkbox sends `acknowledgeBelowThreshold: false`; and that the finalize
  action disappears (replaced by a "already finalized" message) once the
  pool has moved past `PAYMENT_OPEN`.
- `PoolPaymentPage.test.tsx` — a household's own payment screen (PRD §8.4):
  a `null` response reads as a plain "nothing to pay" message with no pay
  button; a `PENDING` payment renders the amount, the required "you're
  paying the class organizer, not ClassPool" disclosure, and a pay action
  that calls `POST .../pay` with `{ method: "CARD" }` and updates to the
  paid state on success; any other state (e.g. `PAID_CASH_RECEIVED`) shows
  plain language with no pay button; and — the privacy check —
  `householdDisplayName` is asserted absent from the page even when a mock
  deliberately includes it.
- `RecordOrderAction.test.tsx` — the organizer's "record the order" action
  (PRD §9.1): the primary confirm path POSTs `{}` (no line overrides) and
  the resulting `Order` is passed to `onRecorded`; the secondary per-line
  editor path types an actual cost/description for one line and asserts the
  exact `purchasePlanLineId`/`actualCostCents`/`actualDescription` payload
  sent; the already-recorded read view renders both an `ABSORBED` and a
  `TOP_UP_CHARGED` line's plain-language outcome side by side and asserts
  neither raw enum value ever appears; and a 409 on submit shows a specific
  already-recorded message.
- `GenerateDistributionAction.test.tsx` — the organizer's one-way "set up
  distribution" action (PRD §9.2/§9.3): shows a specific "record the order
  first" reason instead of a dead button when its own `GET .../order`
  precondition check comes back 409; requires picking a mode and passing
  through the "this can't be undone" step before `POST
  .../distribution/generate` fires with the selected `mode`; re-checks that
  precondition when `refreshKey` bumps (simulating `RecordOrderAction`
  recording an order); and cancelling backs out without calling the API.
- `DistributionPanel.test.tsx` — the organizer's distribution view (PRD
  §9.2/§9.3): renders each household's pick list with multiple summed
  lines; a not-generated 409 renders a message instead of crashing; the
  per-item delivery tracker groups by student and shows "Mark delivered"
  only on still-undelivered items; clicking it calls the deliver endpoint
  for the right item and updates just that row with no full reload; and the
  print button calls `window.print()`.
- `ClassReserveCard.test.tsx` — the organizer's class reserve list (PRD
  §9.4/§19): renders quantity/item name per entry and falls back to "not
  yet noted" — never a raw `null` — when `custodianLocation` is null, plus
  an empty-state message when nothing's been banked.
- `CompletePoolAction.test.tsx` — the organizer's final one-way "close out
  this pool" action: unreachable without first passing through the "this
  can't be undone" step; cancelling backs out without calling the API; a
  409 shows a specific already-complete message; and once `poolState` is
  already `COMPLETED` it renders a warm closing message with no action
  button at all (not just a disabled one).
- `PoolDistributionPage.test.tsx` — a household's own "what you're
  receiving" screen (PRD §9.3): an empty array reads as a plain "nothing to
  show yet" message; a populated response groups items by student and
  renders each one's plain-language delivered/not-yet-delivered status via
  the same `distributionItemStatusLabel` helper the organizer's panel uses.
- `NotificationBell.test.tsx` — the site-wide notification bell (PRD §11.3,
  mounted once in `SiteHeader`): renders nothing (and never calls the
  endpoint) while signed out; shows the unread count as a badge; opening the
  dropdown lists every notification's plain-language `message`; clicking an
  unread one calls `POST /notifications/{id}/read` and navigates to
  `/pools/{poolId}` when it has one; and clicking one with no `poolId`
  neither navigates nor re-calls the read endpoint for an already-read row.
- `SavingsSummaryCard.test.tsx` — the shareable savings-summary card (PRD
  §16.3's "Grade 1 saved $1,118 and reused 397 items with ClassPool" viral
  loop): renders the reused/purchased item counts; shows the
  `estimatedSavingsCents` dollar figure (via `formatCents`) only when it's
  greater than 0; treats the not-yet-reconciled 409 as a quiet "hasn't been
  worked out yet" state rather than an error; and the share action calls
  `navigator.share` when available, falling back to copying
  `shareableMessage` via `navigator.clipboard.writeText` (with a "Copied!"
  confirmation) when it isn't.

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
| `/pools/[id]` | Pool detail — requirement list, add/edit/remove + confirm for an organizer while `DRAFT`, read-only otherwise (Phase 3). While still `DRAFT`, an organizer also sees `ImportRequirementsForm` — the AI-assisted "import from pasted text" path — mounted alongside (never replacing) the manual add form (Phase 11). Once the pool is past `DRAFT`, also links to `/pools/[id]/inventory` for every member and shows the organizer inventory summary panel (Phase 4). While the pool is `OPEN_FOR_INVENTORY`, an organizer also sees `ReconcileAction` — the one-way "work out what still needs buying" step. Once the pool has moved past that (`RECONCILING` or later), everyone sees `MyAllocationPanel` (their own household's status) and an organizer additionally sees `OrganizerAllocationPanel` (the full purchase breakdown) (Phase 6). While still `RECONCILING`, that same panel also embeds a `ProductOfferForm` per item that still needs buying, and the organizer sees `GeneratePurchasePlanAction` — the one-way "work out the cheapest way to buy what's left" step; once a plan exists (`PURCHASE_PROPOSED` or later) the organizer additionally sees `PurchasePlanPanel` (chosen retailer/pack/cost per item, running total, and an approve step) (Phase 7+8). Once a plan exists an organizer also sees `StripeOnboardingCard` (connect a bank account for this classroom); while the pool is `PURCHASE_PROPOSED` they see `GeneratePaymentsAction` — the one-way "open payment for this pool" step, gated on the plan being `APPROVED` and Stripe being `ACTIVE`; once payments exist (`PAYMENT_OPEN` or later) an organizer additionally sees `PaymentsThresholdPanel` (percent collected, the below-90% risk banner, and the finalize action) and `OrganizerPaymentsPanel` (every household's payment, with cash-fallback and refund actions), and everyone sees a link to `/pools/[id]/payment` (Phase 9). Once the pool has entered ordering (`ORDERED` or later), an organizer additionally sees `RecordOrderAction` and `GenerateDistributionAction` while still `ORDERED`, then `DistributionPanel`, `ClassReserveCard`, and `CompletePoolAction` once distribution exists (`DISTRIBUTING` or later); everyone sees a link to `/pools/[id]/distribution` once distribution exists (Phase 10) |
| `/pools/[id]/inventory` | "Shop Your Home First" (PRD §4) — the caller's own household inventory checklist: one +/- stepper row per (requirement, student) they have in this classroom (Phase 4) |
| `/pools/[id]/contribute` | "Offer surplus" (PRD §5.1) — the caller's own pledge screen: one offer card per (requirement, student) they have in this classroom, showing their own pledge status and a withdraw action while still `PLEDGED`. Linked from `/pools/[id]` once the pool is past `DRAFT`, alongside (but visually secondary to, per the "optional/low-pressure" framing) the inventory link (Phase 5). The organizer's confirmation view is not a page — it's `OrganizerContributionsPanel`, embedded directly on `/pools/[id]` next to the inventory summary, same as Phase 4 |
| `/pools/[id]/payment` | The caller's own household payment screen (PRD §8.4) — `GET /pools/{poolId}/payments/mine`. `null` reads as "nothing to pay" (not generated yet, or no residual demand); a `PENDING` payment shows the amount, the required "you're paying the class organizer, not ClassPool" disclosure, and a single "Pay with card" action (a V1 stub — see below); any other state shows plain-language status with no pay button. Linked from `/pools/[id]` once `hasPayments(pool.state)` (Phase 9) |
| `/pools/[id]/distribution` | The caller's own household distribution screen (PRD §9.3) — `GET /pools/{poolId}/distribution/mine`. An empty array reads as "nothing to show yet" (not generated yet, or nothing to receive); a populated response groups items by student and shows each one's plain-language delivered/not-yet-delivered status. Linked from `/pools/[id]` once `hasDistribution(pool.state)` (Phase 10) |

Phase 6's allocation views are all embedded directly on `/pools/[id]`, not
separate pages — same pattern as Phase 4's `InventorySummaryPanel` and
Phase 5's `OrganizerContributionsPanel`. Copy throughout avoids the PRD's
internal "residual demand"/"allocation engine" terms (§6) in favor of plain
language ("work out what still needs to be bought") for both the organizer
action and the two read views (`OrganizerAllocationPanel`,
`MyAllocationPanel`). `allocationStatusLabel` in `src/lib/pool-labels.ts`
is the single source of per-status wording, reused identically by both
read views (same "one sentence, reused verbatim across surfaces" approach
as `contributionStateLabel`) — it takes an optional purchase-quantity
argument so the `PURCHASE_REQUIRED` case can name the actual shortfall
("Still needs 2 — will be part of the class purchase"), which a bare
`Record<AllocationStatus, string>` lookup can't express on its own.

Phase 7+8's product-offer and purchase-plan UI follows the same "embed on
`/pools/[id]`, don't add pages" pattern, and reuses `OrganizerAllocationPanel`
as the mount point for offer entry rather than adding a parallel fetch of the
residual-demand list: that panel already knows, per requirement, whether
`residualDemand > 0`, so it now also fetches `GET /pools/{id}/product-offers`
once (only while `pool.state === "RECONCILING"`, passed in as a `poolState`
prop) and renders a `ProductOfferForm` inline under each requirement that
still needs buying, passing down that requirement's already-added offers and
taking the add/remove callbacks to update its own state — no full reload,
and no second component independently re-deriving "which requirements still
need buying." `GeneratePurchasePlanAction` (mounted alongside it, organizer +
`RECONCILING` only) and `PurchasePlanPanel` (mounted once
`hasPurchasePlan(pool.state)`, a new `pool-labels.ts` helper with the same
"or later" shape as `hasReconciled`) are separate components, same
"transition action" vs. "read view" split as `ReconcileAction`/
`OrganizerAllocationPanel`. `formatCents`/`dollarsToCents` in
`src/lib/money.ts` are this app's first money helpers (no earlier phase
handled currency), establishing the convention going forward: the API always
carries integer cents, and only the UI boundary — a price input, a
`formatCents` call — ever converts to/from a dollar amount. Copy again avoids
the PRD's internal "optimizer"/"integer program"/"bulk-pack" terms (§7-8) in
favor of plain language ("work out the cheapest way to buy what's left").

Phase 9 (Stripe Connect payment collection, PRD §8.4) continues the same
"embed the organizer views on `/pools/[id]`, one dedicated page for a
household's own view" split as every prior phase, plus the same
`hasX(pool.state)`-in-`pool-labels.ts` gating pattern (`hasPayments`, mirror
of `hasPurchasePlan`). `StripeOnboardingCard` is keyed by *classroom*, not
pool — one Stripe account per classroom serves every pool it ever runs — and
treats a 404 from `GET .../stripe-onboarding/status` ("never started") as a
normal not-started state, same "absence is a valid state" idea as
`MyAllocationPanel`'s empty array. There's no real Stripe hosted-onboarding
redirect in this environment, so after starting onboarding the card is
explicit that its "Simulate returning from Stripe" button
(`POST .../stripe-onboarding/complete`) stands in for that redirect, not a
real bank-account connection.

`GeneratePaymentsAction` is the one new "transition action" whose own
preconditions (an *approved* purchase plan, an *ACTIVE* Stripe account)
aren't fully captured by `pool.state` alone — `PURCHASE_PROPOSED` covers
both a still-`PROPOSED` and an already-`APPROVED` plan — so unlike every
earlier one-way action in this app, it checks both preconditions itself on
mount (`GET .../purchase-plan`, `GET .../stripe-onboarding/status`) and
renders a specific, named reason instead of a dead button when either isn't
met, rather than relying purely on "trust the mount point." The one 409 this
button can still reach in normal use (payments already generated) gets the
same generic-conflict handling as everywhere else.

`OrganizerPaymentsPanel` renders `Payment.householdDisplayName` — per the
contract's own doc comment, "same privacy posture as
`Contribution.offeringParentDisplayName`" — so it's mounted only inside the
pool page's `isOrganizer` branch, same boundary `OrganizerContributionsPanel`
draws (see that component's PRIVACY note and its end-to-end visibility
test). `/pools/[id]/payment` (the household's own view, `GET
.../payments/mine`) never receives or renders that field — the contract
notes it's always `null` on that endpoint anyway, but the page doesn't rely
on that alone; `PoolPaymentPage.test.tsx` asserts it stays absent even if a
mock sends it. `paymentStateLabel` in `pool-labels.ts` is shared verbatim by
both surfaces, so `PENDING_CASH`/`PAID_CASH_RECEIVED` never reach either
screen as raw enum values.

`PaymentsThresholdPanel`'s finalize action treats "proceed below the 90%
threshold" with the same weight as `PurchasePlanPanel`'s
approve-with-money-at-stake confirm, but stronger: below threshold, the
confirm button stays disabled until an explicit checkbox is ticked (not just
a second click), then sends `acknowledgeBelowThreshold: true`; at/above
threshold it's a single deliberate confirm step with no checkbox, sending
`acknowledgeBelowThreshold: false`. `pay` (a household paying their own
`Payment`) is a V1 stub per the task brief — it immediately marks the
payment `PAID` with no real Stripe redirect or card entry — but the
PRD-required "you're paying the class organizer, not ClassPool" disclosure
is still shown before the button regardless, since that's a real product
requirement independent of whether Stripe is live. There's no organizer-name
field anywhere in the contract (`Classroom.teacherLabel` names the teacher,
not necessarily the organizing parent), so the disclosure uses the honest
fallback "the class organizer" rather than inventing one.

Phase 10 (ordering & distribution, PRD §9) continues the same "embed the
organizer views on `/pools/[id]`, one dedicated page for a household's own
view" split as every prior phase. It also reproduces (deliberately) the
`refreshKey`/callback wiring lesson Phase 9 already had to work out:
`RecordOrderAction` (`POST .../order`) does NOT itself change `pool.state`
— a pool stays `ORDERED` whether or not an order has been recorded yet —
so `GenerateDistributionAction`'s own "has an order been recorded?"
precondition can't trust `pool.state` alone any more than
`GeneratePaymentsAction`'s "plan approved + Stripe active" precondition
could. It checks `GET .../order` itself on mount, and the pool page bumps
an `orderRefreshKey` (passed to `GenerateDistributionAction` as
`refreshKey`) from `RecordOrderAction`'s own `onRecorded` callback — the
exact same shape as `paymentPreconditionsRefreshKey` being bumped by
`PurchasePlanPanel.onApproved`/`StripeOnboardingCard.onActive` in Phase 9,
because these are two separate, self-contained sibling components with no
other link back to each other. `hasEnteredOrdering`/`hasDistribution` in
`pool-labels.ts` gate the section's mount point the same "or later" way as
every earlier phase's `hasX` helper — `hasDistribution` in particular is a
real state-transition boundary (`generateDistribution` is what flips
`ORDERED -> DISTRIBUTING`), so `DistributionPanel`/`ClassReserveCard`/
`CompletePoolAction` can trust it with no self-check of their own, the same
way `PurchasePlanPanel`/`StripeOnboardingCard` trust `hasPurchasePlan`.

`RecordOrderAction` itself doubles as both the recording action (no order
yet) and the read view of what happened (one already recorded) — the same
"one component, both a read view and a commit action" shape
`PurchasePlanPanel` established — since, again, `pool.state` alone can't
distinguish those two sub-states while `ORDERED`. Recording with no line
overrides (the common case — most orders go exactly as planned) gets a
single deliberate confirm step, same weight as `PurchasePlanPanel`'s
approve action, rather than the full two-screen amber-then-red treatment
reserved for actions that lock in a `pool.state` transition
(`GenerateDistributionAction`, `CompletePoolAction`). Per-line substitution
outcomes (`ABSORBED`/`TOP_UP_CHARGED`) are turned into plain language by
`orderLineSubstitutionMessage` in `pool-labels.ts`, never shown as the raw
enum value, following the same "plain language over PRD/contract jargon"
convention as `allocationStatusLabel`/`paymentStateLabel`.

`DistributionPanel` keeps the printable per-household pick lists (PRD
§9.2 update's "Family A: 12 pencils, 2 notebooks…" artifact, and the actual
point of this feature) and the raw per-item delivery tracker in one
component, grouped by student rather than a flat table, since both read
from the same `GET .../distribution` call and act on the same underlying
items. Printing is a plain `window.print()` button plus a scoped
`@media print` rule that hides everything on the page except the pick-list
region, rather than a separate print-preview route — simplest thing that
produces a genuinely clean, page-ready hand-out. `CompletePoolAction`
folds its own "already complete" read state into the same component
(mirroring `PaymentsThresholdPanel`'s "already finalized" branch) rather
than a separate always-mounted read-only sibling, and leans into PRD's
"savings shown" moment of warmth in its copy without fabricating an actual
savings figure (that computation is explicitly a later phase's job).

Phase 11 (AI-assisted requirement import, PRD §3.1/§3.2) adds a second,
equally-first-class way to build a `DRAFT` pool's requirement list —
`ImportRequirementsForm` (`POST /pools/{poolId}/requirement-sources`) is
mounted directly below `RequirementForm` on `/pools/[id]`, never in place of
it, per the PRD's explicit "manual entry stays available indefinitely"
principle (the section heading literally says "Or import from pasted
text"). The source-type picker uses the same `*_OPTIONS` array + label-helper
pattern `STRICTNESS_OPTIONS`/`strictnessLabel` established in Phase 3
(`REQUIREMENT_SOURCE_TYPE_OPTIONS`/`requirementSourceTypeLabel` in
`pool-labels.ts`), covering only the three values the import endpoint
actually accepts (`PASTED_EMAIL`/`PASTED_PORTAL`/`PASTED_MESSAGE`) — the
wider `RequirementSource.sourceType` enum also carries `PDF`/`PHOTO`/
`SCREENSHOT`/`WORD_DOC` (documented file-upload kinds V1 doesn't build,
since they need object storage) and `MANUAL` (never a value this app sends),
so the options list is deliberately narrower than the full response type.

On success, the form never silently drops the extracted items into the list
with no acknowledgment: it shows a `role="status"` summary that separately
counts and names items that came back `EXTRACTED` ("ready to review," at/above
the API's 0.85 confidence threshold) versus `NEEDS_REVIEW` (below it, worded
"need a closer look before you can confirm the list") — two different
sentences, not one blended count — before calling `onImported` with the whole
batch. `onImported` mirrors `RequirementForm`'s existing `onSaved` callback
shape exactly (the pool page's `updatePool` appends to `pool.requirements`
and bumps `requirementCount`), just for an array instead of one item, so
there's no second, parallel way of updating pool state on the page.

`RequirementListItem` renders AI provenance only when
`requirement.confidence != null` — manual entries (`confidence: null`,
unconditionally, per PRD §3.2) show nothing new at all, so this phase adds no
visual noise to the Phase 3 manual-entry path it sits beside. Where it does
apply, `requirementNeedsReview`/`requirementConfidenceLabel` in
`pool-labels.ts` turn `state === "NEEDS_REVIEW"` into a visibly and textually
distinct badge ("Needs a closer look — N% confidence," amber, plus an amber
row background) from `state === "EXTRACTED"` ("AI-extracted — N% confidence,"
neutral gray) — Phase 3 defined `NEEDS_REVIEW` in the contract but never
actually produced it (manual entries are always `EXTRACTED`), so this is the
first UI to give it a real, distinct appearance. `sourceEvidence` — the PRD's
explicit transparency requirement ("every extracted field retains source
evidence") — is shown via a native `<details>/<summary>` disclosure ("Why was
this extracted this way?") rather than always-visible text, keeping the list
scannable while keeping the evidence one click away and present in the DOM
either way (so it's genuinely accessible, not hidden behind JS state). This
phase does not change `ConfirmPoolAction` — confirming still only requires at
least one requirement, matching the contract's own `confirmPool` behavior
(it moves every `EXTRACTED`/`NEEDS_REVIEW` requirement to `CONFIRMED`
unconditionally) — so an organizer can still confirm a list with unreviewed
`NEEDS_REVIEW` rows on it; the badge is a clear signal, not a hard gate, since
the contract doesn't expose one to build against.

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
12. **`generatePurchasePlan`'s 409 collapses three different failure reasons
    into one status code with no distinguishing response field (Phase 7+8).**
    Per the contract's own description, that 409 covers "pool is not
    RECONCILING, a plan already exists, OR a requirement with residual
    demand has no offers" — but the response schema declares no body content
    to tell those apart, and every other 409 in this app is already handled
    by status code alone (never by parsing an error body), so there's no
    existing pattern to extend here either. `GeneratePurchasePlanAction`
    shows its specific "add a price option first" message for every 409,
    which is accurate given the page's own gating: it only mounts this
    action while `pool.state === "RECONCILING"` and no plan has been
    generated yet (mirrored in `hasPurchasePlan`'s pool-state check), so the
    other two reasons are unreachable through this button in normal use —
    same "trust the mount point" reasoning `OrganizerAllocationPanel`
    already relies on for its own not-reconciled 409. Worth a follow-up
    contract conversation if a future phase wants the three cases
    distinguished (e.g. a `reason` field or per-requirement detail in the
    409 body) rather than relying on client-side gating to make the
    single message accurate.
13. **No organizer-name field exists anywhere in the contract (Phase 9).**
    The PRD-required payment disclosure ("You're paying [organizer name],
    the class organizer — not ClassPool") names a specific person, but
    neither `Classroom`, `Pool`/`PoolDetail`, nor `Payment` carries one —
    `Classroom.teacherLabel` names the teacher the class is *for*, not
    necessarily the parent who organized the pool, and there's no
    `Membership`-level "who is the organizer" identity exposed to a
    non-organizer caller. `/pools/[id]/payment` uses the honest fallback
    "the class organizer" rather than inventing a name field the contract
    doesn't define. Worth a follow-up contract conversation if product wants
    the real organizer's name in this copy — likely a new field on `Pool`/
    `PoolDetail` or `Payment` naming the organizing household.
14. **`generatePayments`'s 409 also collapses multiple failure reasons with
    no distinguishing field (Phase 9)**, same shape as item 12 above ("no
    approved purchase plan, Stripe onboarding isn't ACTIVE, or payments
    already exist"). Here there's an added wrinkle: unlike the purchase-plan
    generator, this button's own gating (`pool.state === "PURCHASE_PROPOSED"`)
    genuinely can't distinguish "plan not approved yet" from "Stripe not
    connected yet" — both keep the pool in `PURCHASE_PROPOSED`. Rather than
    rely on "trust the mount point" here, `GeneratePaymentsAction` checks
    both preconditions itself (`GET .../purchase-plan` for `state ===
    "APPROVED"`, `GET .../stripe-onboarding/status` for `status ===
    "ACTIVE"`) and shows a specific, named reason instead of the action when
    either isn't met, so the only 409 actually reachable by clicking the
    button in normal use is "payments already exist" — same generic-conflict
    message used elsewhere for an unreachable-in-practice case.
15. ~~`PurchasePlanLine` had no `id` field, so `recordOrder`'s
    `purchasePlanLineId` had no unambiguous value to send.~~ Fixed:
    `PurchasePlanLine.id` was added to the contract (and `PurchasePlanService`
    now returns it) as part of Phase 10 integration, so `RecordOrderAction`
    sends the real row id — correct even when a requirement's plan spans more
    than one offer/line.
16. **`confirmPool` doesn't require every requirement to be reviewed first
    (Phase 11).** PRD §3.2's AI-import update implies `NEEDS_REVIEW` items
    should get organizer attention "before you can confirm the list" (see
    `ImportRequirementsForm`'s own results-summary copy), but the contract's
    `confirmPool` operation only requires `requirementCount >= 1` and
    unconditionally moves every `EXTRACTED`/`NEEDS_REVIEW` requirement to
    `CONFIRMED` — there's no 409 case for "some requirements still need
    review," and `ConfirmPoolAction` (Phase 3, unchanged here) has no way to
    block on it without inventing a client-only check the contract doesn't
    back. `RequirementListItem` gives `NEEDS_REVIEW` a clearly distinct,
    attention-drawing badge, but confirming a list with unreviewed rows on it
    is still possible today. Worth a follow-up contract conversation if
    product wants a real gate (e.g. `confirmPool` 409-ing while any
    requirement is `NEEDS_REVIEW`) rather than a UI-only nudge.
