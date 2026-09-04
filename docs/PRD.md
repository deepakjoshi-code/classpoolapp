# ClassPool — Product & Engineering Blueprint (v1.4)

Working specification — Web/PWA first
Base document: *ClassPool Full Product & Engineering Blueprint* (original blueprint).
This revision is a PM pass over that original: gaps are called out inline as **`> 🔧 PM UPDATE`** blocks. Everything outside those blocks is the original spec, condensed for the repo. Nothing marked PM UPDATE is optional polish — each one blocks a real V1 flow (money, trust, or data model) if left unresolved.

**v1.2 changes**: resolves physical custody of Class Reserve (previously undefined — see §9.4), plus a second gap pass covering class/school deduplication, substitution-equivalence authoring, self-reported-inventory trust, and organizer physical labor.

**v1.3 changes**: adds a cash/check fallback for families without a card or digital wallet, since Stripe checkout (§8.4) otherwise excludes them entirely with no substitute — see §8.4.

**v1.4 changes — a rigor pass, not just new gaps.** Two kinds of fix: (1) **structural** — §5, §7, §9, and §11 were cited throughout as §5.1–5.4, §7.1–7.4, §9.1–9.4, §11.1–11.4 but never actually carried those headings, so every such cross-reference in this document was previously unresolvable; all four sections now have real subsection headings that match. (2) **internal consistency** — checking earlier fixes against each other and against the original surfaced eight more gaps: a Payment Unlock Gate condition (§14) with no backing data field (§13.1), two undocumented state machines with no stated relationship (§13.3), a late-joiner rule that assumes a pool that's still open (§13.3), a stranded-reserve rule that assumes a successor class exists yet (§9.4), a silent-absorption rule that assumes a next pool exists to absorb into (§9.1), a combined household payment view that isn't actually payable as one transaction (§12), a monetization stage with no corresponding build-order feature (§7.4), and a Lend-item fix that needed to be marked against the original's own "later" scoping (§5.4).

---

## 0. Clarifications on record

> 🔧 **PM UPDATE — answering two open questions directly, since they gate everything else below:**
> - **Platform**: PWA only for V1. Mobile-first, installable to Home Screen, standalone display, one Next.js codebase for iPhone/Android/desktop. No native iOS/Android app in V1 (§11, §17.2).
> - **Customer**: **Parents are the customer.** They join, own the household data, pay, and receive goods — they are the daily active user and the one whose trust and money are at stake. The **Class Organizer is a parent volunteer** with elevated permissions (usually the one who happened to start the class), not a separate persona. **Teacher is optional and verification-only — never required to use the app, never handles money, never blocks a pool if absent.** This was already the stated intent in the original executive summary; the gap is that later sections (payments, trust/fraud, organizer succession) don't consistently protect that boundary. Fixed below in §2, §8, §14.

---

## 1. Product Thesis

Turn a school or class requirement into the cheapest collective fulfillment plan by reusing what families already own, exchanging extras, and pooling only the remaining purchases.

**Core loop**: Requirement → Reuse → Share/Exchange → Residual Demand → Optimized Group Purchase → Distribution

**Product rule**: ClassPool reduces waste and purchasing before it monetizes commerce. The economic loop is the product; AI extraction is an accelerator, not the company.

---

## 2. Users, Accounts, Schools and Classes

### 2.1 Roles

| Role | Primary capabilities |
|---|---|
| **Parent** | Join class, report owned items, offer extras, see allocations, pay, receive items. |
| **Class Organizer** | A parent with elevated permissions: create class/pools, verify requirements, manage deadlines, confirm contributions, purchase and distribution. |
| **Teacher** | Optional. Submit or verify requirement lists without ever handling money. |
| **School/PTA Admin** | Manage multiple classes, school-wide pools, preferred suppliers, reporting. |
| **ClassPool Admin** | Support, fraud review, supplier management, refunds, platform analytics, access controls. |

> 🔧 **PM UPDATE — Organizer is not a distinct account type, it's a Parent + a permission grant on a Classroom.** Any parent should be able to become an organizer for a class that has none (self-serve creation), because the viral loop in §16.3 depends on parents starting classes without waiting on a school. Modeled as `Membership.role = ORGANIZER` on top of the same `User`/`ParentProfile`, not a separate signup path.

> 🔧 **PM UPDATE — Organizer succession (missing in original).** A single volunteer organizer is a single point of failure for a flow that involves other families' money. V1 needs:
> - **Co-Organizer**: organizer can promote another parent to co-organizer at any time.
> - **Inactive-organizer escalation**: if a pool has money collected (`PAYMENT_OPEN` or later) and the organizer hasn't acted in a configurable window (e.g. 7 days) while parents are blocked, ClassPool Admin can reassign or add a co-organizer on request from any member of that class. This is a support workflow, not automatic — auto-reassignment of who controls other people's money is itself a fraud vector.

### 2.2 Parent onboarding

Arrives via class link/QR (`classpool.app/join/7H2KQ`). Landing page shows school/class context and participation progress *before* auth.

- Continue with Google / Continue with Apple / Email magic link. No password creation.
- Minimum data only: display name, email, optional phone (delivery notifications only), child first name or initials, class membership. No DOB, no full student profile.

> 🔧 **PM UPDATE — notification consent (missing).** Phone number is optional and must stay opt-in-only for SMS. Default notification channels are **web push (PWA) + email**; SMS requires an explicit second opt-in with its own toggle, not implied by "optional phone." See §11.3 update.

### 2.3 School/class hierarchy

`School → Academic Year → Grade → Class → Pool → Requirement List → Items`

A class can run multiple pools per year (Fall Supplies, Science Project, Costume, Book, Uniform, Classroom Contribution).

> 🔧 **PM UPDATE — Household/Student entities are missing from this hierarchy and from the data model in §13.** A parent with two kids in two different classes has no first-class way to see both in one place — §12.2 ("Parent Home") only mockups a single class. Add:
> `ParentProfile → Household → Student(s)`, with each `Membership` linking a `Student` (not just a `ParentProfile`) to a `Classroom`. This makes a consolidated multi-child, multi-class parent dashboard possible instead of forcing parents to bounce between disconnected single-class views. See §12 and §13 updates.

> 🔧 **PM UPDATE — school/class deduplication has no defined workflow, and this is more serious than it first looks.** The admin console (§12) already lists "Duplicate schools/classes" as something admin can *view*, but nothing describes how it's *prevented* or *fixed*. The failure mode: two parents at the same school both start "Lincoln Elementary, Ms. Smith, Grade 1" as separate classes, unaware of each other — now half the parents pool with one group and half with the other, and the entire value proposition (bulk pricing depends on pooled volume) is cut in half for both, silently. Since the whole product is a network-effect play on one class being one pool, this is a core-loop risk, not an admin nicety. Fix:
> - **At creation time** (§2.4), typing a school name fuzzy-matches against existing `School` records first (and existing `Classroom` records under it) and prompts "Is this your class?" before allowing a new one — creation is still self-serve, just with a dedup check in front of it, not gated by anyone's approval.
> - **Admin merge tool**: when duplicates slip through anyway, admin needs an actual merge action (union `Membership`s, keep the pool with the earlier `DRAFT`/open state, notify both organizers), not just a read-only "flagged" list.

### 2.4 Create a class

Select/create school → choose grade, teacher/class label, school year → enter approximate student count → generate join URL + QR → one-tap invite text for email/text/parent groups.

---

## 3. Requirement Ingestion and Verification

### 3.1 Import sources
PDF, photo, screenshot, Word doc, pasted email/portal text/message. Gmail/Outlook/portal integrations are later, not required for V1.

### 3.2 AI extraction
Every extracted field retains source evidence + confidence score.

```
name: Glue Stick
quantityPerStudent: 4
brand: Elmer's
strictness: equivalent_allowed
sourceEvidence: "4 Elmer's glue sticks"
confidence: 0.96
```

> 🔧 **PM UPDATE — manual entry is a permanent parallel path, not just a pre-AI placeholder.** The build order (§17.3) sequences manual entry before AI ingestion, which correctly de-risks the build — but the original reads as if AI *replaces* manual entry once shipped. It should stay available indefinitely: organizers who don't trust an AI-parsed list, or whose list didn't parse cleanly, always have manual add/edit as a first-class option, not a fallback buried behind a failure state.

### 3.3 Organizer verification
Nothing is financially actionable until a human organizer verifies. Correct/Edit/Remove per item. Modes: Exact item / Equivalent allowed / Generic.

**AI safety rule**: AI may interpret messy text but must never silently invent a requirement or decide a strict spec can be substituted.

### 3.4 Aggregate class demand
Per-student quantity × confirmed participating students = total demand.

---

## 4. Household Inventory and Reuse

**"Shop Your Home First"** is the first parent action, before shopping. Quick single-screen stepper (+/- and "Have" toggles) across the whole list, not item-by-item. Immediate value message after completion: *"You already have $31 worth of your list."*

States: Required quantity / Owned and retained / Owned surplus offered / Still needed / Item condition (for reusable goods).

> 🔧 **PM UPDATE — self-reported inventory has no verification, and that should be a stated decision, not a silent gap.** A parent could under-report ("I have 0 pencils") to get the class to buy them new ones, or over-report to skip a purchase they actually need. Recommendation: **accept this as low-stakes risk for V1 and don't build verification** — the dollar amount at stake per item is small (a $2 glue stick), the social cost of visibly gaming a shared class list is high (organizer can see identity per §5.3), and verification (photos, receipts) would add friction to the one step that's supposed to feel instant ("You already have $31 worth of your list"). This gets revisited only if it shows up as real leakage once §18's validation pilot runs — it's an explicit accepted risk, not a fix to build now.

---

## 5. Surplus Contributions and Exchange Pool

### 5.1 Offer surplus
Donate/Give (default for consumables) · Lend (reusable goods, later) · Sell cheaply (uniforms, books, calculators, sports gear, later) · Keep extras (always available).

### 5.2 Class exchange pool
Allocates automatically — no one-to-one parent negotiation for ordinary consumables.

### 5.3 Privacy model
Aggregate contribution counts by default; organizer can see contributor identity for drop-off coordination; no public household-level inventory disclosure; **no open parent-to-parent chat in V1.**

### 5.4 Contribution lifecycle
`PLEDGED → RECEIVED → ALLOCATED → DISTRIBUTED`. Purchasing math must use confirmed (received), not pledged, surplus.

> 🔧 **PM UPDATE — the lifecycle above only fits consumables (Donate/Give). "Lend" is explicitly in scope (§5.1, and the whole Uniform/Costume/Sports pool types in §10) but has no return path.** A lent item that's never given back is a real, common failure mode (uniforms especially). Add lend-specific states: `PLEDGED → RECEIVED → ALLOCATED → DISTRIBUTED → RETURN_DUE → RETURNED` with `OVERDUE` and `LOST_OR_DAMAGED` as terminal/escalation states off `RETURN_DUE`. This also needs a due date and a reminder notification (§11.3).
>
> **Consistency note found in this pass**: §5.1 itself already marks Lend and Sell as "later," not V1 — so this state-machine addition is a data-model decision to make *now* (cheap: it's just enum states), not a claim that Lend ships in V1. When Lend does ship, the states already exist rather than needing a schema migration on top of whatever V1 shipped with. Nothing about V1 scope changes here.

---

## 6. Residual Demand and Allocation Engine

```
TotalRequired
 - ParentOwnedAndRetained
 - ConfirmedSurplusContributed
 - ClassReserveAvailable
 = ResidualDemand
```

Deterministic business logic, **not** LLM reasoning. Inputs: requirements, household inventory, confirmed surplus, class reserve, compatibility rules, condition rules, deadlines. Output per household/item pair: `self fulfilled` / `pool fulfilled` / `purchase required`.

> 🔧 **PM UPDATE — "compatibility rules" is listed as an allocation-engine input, but nothing specifies who decides two products are equivalent, or how.** For an `equivalent_allowed` item (e.g. "any brand glue stick is fine"), the optimizer needs to know which catalog products actually qualify as substitutes before it can pick the cheapest one — that decision can't be left implicit, and per §15's AI boundary rule it must not be made by AI at purchase time. Fix: substitution eligibility is **organizer-authored per requirement**, scoped narrowly — e.g. organizer picks a product category + any required attributes (size, non-toxic, washable) rather than approving individual SKUs one by one, and that becomes the filter the optimizer runs against approved catalog offers (§7.4). AI may *suggest* candidate matches for the organizer to approve (already allowed per §15's table), but the approved rule itself is a deterministic filter, not a judgment call made per purchase.

---

## 7. Bulk Purchase Optimization

### 7.1 Pack-size optimization
Optimize residual demand against retailer pack sizes rather than one retail unit per family.

```
Need: 320 pencils
A: 24-pack @ $4.99   B: 48-pack @ $8.49   C: 144-pack @ $18.99
Find non-negative integers x1, x2, x3 such that: 24x1 + 48x2 + 144x3 >= 320
minimize: cost + waste penalty + shipping
```

### 7.2 Why group pack-splitting matters
6 kids × 5 markers = 30 needed. Independent shopping (6 × 12-packs) = 72 markers, 42 wasted. ClassPool (3 × 12-packs) = 36 markers, 6 wasted.

### 7.3 Product-offer inputs
Pack qty, price, shipping, sales tax, delivery date, minimum order, retailer reliability, substitution eligibility, affiliate economics (later).

### 7.4 Retail sources
Amazon, Walmart, Target, Staples, Office Depot, Costco/Sam's Club, approved school suppliers, India-specific retailers later. Prefer official/affiliate APIs or supplier feeds; avoid unauthorized scraping where merchant terms prohibit it.

> 🔧 **PM UPDATE — §16.4 names "V1 affiliate revenue on residual purchases" as the first monetization stage, but nothing in the V1 build order (§17.3) actually builds the mechanism that earns it.** Affiliate revenue only accrues if the organizer's purchase click-throughs an affiliate-tagged link — but §8.5 has the organizer buying externally on their own, with no described in-app "Buy on Amazon" button generating that click. Fix: the Purchase Plan screen (surfaced in Phase 8 of the build order) needs each `ProductOffer` to carry an affiliate-tagged outbound URL, and the organizer's "buy" action in Phase 10 should be a tracked click-through, not just a plan the organizer reads and independently shops from memory. Without this, "V1 affiliate revenue" in §16.4 is aspirational, not buildable as scoped.

---

## 8. Group Proposal, Family Billing and Payments

### 8.1–8.3 Proposal, household bill, fairness
Show class-level savings story (original → after reuse → after exchange → bulk-optimized) and a per-household bill (original value − already owned − pool contributions + bulk purchase = total). **V1 fairness model is need-based**: each family pays for its own residual items; contributions to other families are voluntary donations unless the organizer later enables a credit-based model.

### 8.4 Payments — original spec
Stripe (US), Razorpay/UPI (India, later). Never store raw card data. States: Pending/Paid/Failed/Refunded/Partially Refunded. Organizer sees completion counts only, not public overdue names.

> 🔧 **PM UPDATE — this is the single biggest unresolved decision in the whole document, and it's a legal/liability question, not a UX one.** §8.4 says "Use Stripe," but §8.5 says V1 is "Organizer purchases externally" — those two statements are only compatible if we're explicit about *where the money actually lands*, and the original never says. If ClassPool collects parent payments into a ClassPool-controlled balance and later pays it out to an organizer, ClassPool is functionally holding and moving other people's money — which starts to look like money transmission and pulls in a different regulatory/compliance bar than a group-purchase coordination app should carry in V1.
>
> **Decision for V1**: use **Stripe Connect (destination charges) to the organizer's own Stripe Express account.** ClassPool never holds a balance; each parent's payment settles directly to the organizer who will make the purchase, minus Stripe's processing fee (and 0% ClassPool platform fee in V1, monetized later per §16.4 instead). Concretely:
> - Organizer completes lightweight Stripe Express onboarding (identity, bank account) the first time they open a pool for payment.
> - Every parent payment screen shows an explicit disclosure: *"You're paying [Organizer name], the class organizer — not ClassPool."* This is both an honesty requirement and a liability boundary.
> - This is what makes "Organizer purchases externally, ClassPool generates the plan" actually consistent end to end, and defers any marketplace-escrow model (ClassPool holding funds, refunding centrally) to the "Later — ClassPool checkout" stage in §8.5, where it belongs.

> 🔧 **PM UPDATE — under-collection risk (missing).** Nothing in the original addresses what happens when the purchase plan is ready but not every family has paid by the deadline — extremely common for school fundraisers, and the organizer is a volunteer, not a merchant with working capital. V1 needs a **payment threshold gate**: the "Organizer purchases" action stays visible but shows a risk banner ("$212 of $1,056 still unpaid — 4 families") and requires an explicit organizer acknowledgment to proceed below a platform-set threshold (default 90% collected — set once at the platform level, not editable per-organizer, since letting an organizer lower their own safety threshold would defeat the point of having one). This doesn't solve the risk, but it stops the app from silently implying the organizer is covered when they aren't.

> 🔧 **PM UPDATE — refund/cancellation triggers (missing).** Payment states already include Refunded/Partially Refunded, but no business rule triggers them. Minimum V1 rule: full refund if the child withdraws from class/school **before** the pool reaches `ORDERED`; no refund after `ORDERED` — instead the paid-for item is redirected into Class Reserve (§9.4) for reallocation or resale credit. This needs to exist before payments ship, not after the first support ticket.

> 🔧 **PM UPDATE — Stripe checkout is card/Apple Pay/Google Pay only, and V1 has no fallback for a family without one.** This is a real exclusion, not an edge case: some families are unbanked, card-averse, or simply prefer handing over cash — and unlike the organizer (who goes through Stripe Express onboarding), parents never create any account, so there's no way to substitute a different digital payment method without rethinking the checkout itself. Recommend a **manual cash/check fallback for V1**, not a second payment integration: a `Payment` can be marked `Pending — Cash` by the organizer, who separately records `Paid — Cash Received` once collected in person. This keeps every household's item on the same allocation/distribution rails (§6, §9) regardless of how they paid — the only difference is Stripe never touches that household's transaction, and the organizer is trusted to log it accurately (same accepted-risk posture as self-reported inventory, §4). It does mean a cash-paying household adds a small manual bookkeeping step for the organizer, which is a fair trade against locking out families with no card.

---

## 9. Ordering, Distribution and Class Reserve

### 9.1 Purchase and receipt recording
Organizer marks products ordered, attaches receipt, records substitutions, tracks short shipments/refunds.

> 🔧 **PM UPDATE — post-purchase substitution workflow is named ("record substitutions made after optimizer proposal") but not specified.** When a SKU the optimizer priced goes out of stock and the organizer buys a different pack size/brand, the per-household bill parents already paid may no longer match what's distributed. V1 needs a concrete threshold, not just "record it": substitution is recorded against the `PurchasePlanLine`; if the resulting cost delta is **≤10% of that line's total, it's absorbed silently** (folded into the next pool's cost estimate, no re-billing); **above 10%, the organizer is prompted to either eat the difference or trigger a small top-up charge** to the affected households — never a manual side conversation with no record.
>
> **Consistency check found in this pass**: "absorbed silently, folded into the next pool's estimate" quietly assumes there *is* a next pool. For a graduating class's last-ever pool, there's nowhere to fold a small delta into — and the organizer isn't supposed to be out-of-pocket at all (§8.5, worked example §22 Phase 9). Fix: if `Classroom` has no successor pool expected (same signal used by §9.4's stranded-reserve prompt below), route *any* delta, even ≤10%, through the top-up path instead of silent absorption — "no next pool to hide it in" and "no organizer out-of-pocket" can't both hold otherwise.

### 9.2 Distribution modes
Classroom desks, school lobby/event pickup, household allocation bags with printable labels.

### 9.3 Distribution checklist
Organizer marks each household bundle Delivered. Parents receive a ready-for-pickup or delivered notification.

> 🔧 **PM UPDATE — the physical labor of distribution is under-designed, and organizer burnout is a direct threat to the viral loop (§16.3 depends on organizers repeating).** "Printable labels" is the only tool mentioned, but the actual work is: open a 144-pack, count out exactly the right number of pencils per household, and get it right for 25 families without mixing up bags. §18 even tracks "organizer effort" as a validation metric but nothing in the product reduces it. Add a **per-household pick list** — generated straight from the `Allocation` output, printable or exportable, listing exact quantities per bag in one pass ("Family A: 12 pencils, 2 notebooks, 4 glue sticks") — this already exists as data, it just needs to be a first-class exportable artifact, not something the organizer reconstructs by hand from the dashboard.

### 9.4 Class reserve
Bulk overage becomes reusable reserve rather than waste; next pool consumes reserve before asking families to buy new.

> 🔧 **PM UPDATE — physical custody of Class Reserve is undefined in the original, and it's a real gap: someone has to physically hold leftover inventory, and the app can't leave that implicit.** Worked example: residual demand is 10 pens, cheapest offer is a 20-pack, organizer buys one. Here's what happens to all 20, concretely:
> - The `PurchasePlanLine` records qty=20. The **Allocation engine already assigned the 10 needed pens to specific households** before purchase (§6) — those 10 are what actually gets bagged: the organizer (or whoever receives the shipment) splits the pack, and the 10 allocated pens go into the normal distribution flow — classroom desks, lobby pickup, or labeled household bags per §9.2/9.3 — matching each household's `DistributionItem` record, same as every other item on the list. Nothing new there.
> - The other **10 are surplus with no household attached.** They don't get bagged for anyone, and they aren't given away free or rented to families — no transaction happens on them at all, because their cost was never billed to any household separately; it's inside the price of "one 20-pack," which the group already paid for as the cheapest way to cover 10 needed pens (§7.1's "waste penalty" is exactly this cost, already priced in).
> - **Default physical custodian: the classroom, not the organizer's home.** Recommend Class Reserve defaults to living in the teacher's classroom supply cabinet, not a volunteer parent's house, because: (a) the teacher is stable across a school year while the organizer role can turn over mid-year (§2.1's succession problem), (b) reserve items are consumed by kids at school, so the classroom is where they're needed anyway, (c) it avoids a parent's home quietly becoming an unofficial warehouse with no accountability if that parent moves or stops volunteering. This doesn't require the teacher to do anything beyond what teachers already do (maintain a supply shelf) — it's not new work, and it doesn't touch money or verification, so it doesn't conflict with "teacher never handles money" (§2.1).
> - **Data model**: `ClassReserve` needs a `custodianLocation` field (free-text label like "Ms. Smith's classroom, supply cabinet") logged by the organizer at intake, not a literal tracked address. The organizer stays accountable for the *record* (what's in reserve, per §12's dashboard) even when the *physical* item sits in the classroom.
> - **Next pool draws it down automatically**: when the next pool's residual-demand calculation runs (§6.1), `ClassReserveAvailable` is checked before the optimizer buys anything new — the 10 reserved pens reduce that pool's residual demand by 10, lowering everyone's bill, with no per-family credit for who originally "paid into" the reserve (it was already priced into last year's per-unit bulk cost, same equity logic as §8.3's need-based model).
> - **Stranded reserve (graduating class / no next pool)**: if a class has no next pool — most commonly the graduating grade at year-end — reserve items don't have anywhere to roll forward to. Default rule: organizer is prompted at pool completion to either (a) donate remaining reserve to the school's general supply closet (logged as a `Transfer` to a school-level reserve rather than a class-level one — useful since `School` already sits above `Classroom` in the hierarchy, §2.3), or (b) hand it to next year's incoming class at the same grade **if that class already exists in the system at hand-off time** — it usually won't yet (§2.4 has next year's organizer creating it fresh, likely months later), so (a) donate-to-school is the practical V1 default and (b) is only reachable once ClassPool supports pre-creating a successor class ahead of its first pool, which isn't in V1 scope. No family gets a refund for stranded reserve — it was priced in when purchased, same as above.

---

## 10. Additional Pool Types and Retention

Project / Costume / Book / Uniform / Sports / Party-Event / Classroom Contribution pools reuse the same requirement/reuse/residual/fulfillment architecture. Local services marketplace is explicitly later, not a V1 starting point.

**Year-end reuse**: prompt families to mark durable items (scissors, calculators, headphones, rulers, binders, folders) as carry-forward, improving next-year forecasting.

---

## 11. Notifications and PWA / Home Screen Experience

### 11.1 Why PWA first
One codebase for iPhone, Android and desktop; installable to Home Screen, opens standalone.

### 11.2 Manifest and app behavior
PWA manifest: name/short_name/start_url/`display: standalone`/icons/theme. Installable, standalone chrome-free display, responsive safe areas, cached app shell + offline fallback, web push where supported, mobile-first touch targets.

### 11.3 Notification events
Class invite/new pool, complete inventory, contribution allocated, reuse period ending, payment due, purchase completed, bundle ready, pool completed + savings summary.

> 🔧 **PM UPDATE — channel priority and consent, tying back to §2.2.** Default: web push (PWA) + email, on by default as part of joining a class (operational, not marketing). SMS is opt-in only, off by default, and only offered when a phone number was actually provided. "Lend item due back" reminder (from the §5.4 update) is added to this event list.

### 11.4 Offline behavior
Load app shell, show last-cached pool state, queue simple local inventory edits, sync carefully on reconnect. **No offline payments/commerce in V1.**

---

## 12. Parent, Organizer and Admin Interfaces

Mobile nav: `HOME | POOL | SHARE | ORDERS | PROFILE`

**Parent Home** (single-pool mock): readiness %, checklist (inventory complete / exchanges allocated / payment due), class savings vs. your savings, pay CTA, next pool teaser.

**Organizer dashboard**: joined/inventory-completed counts, items required/owned/contributed/remaining, estimated savings, plus review/generate/view actions for residual demand, purchase plan, missing responses, unreceived contributions, unpaid balances, requirement clarifications.

**Platform admin console**: users/roles, schools/classes/pools, suppliers/catalog, transactions/refunds, flagged content, AI extraction failures, duplicate schools/classes, analytics, feature flags, audit trail.

> 🔧 **PM UPDATE — HOME needs to be multi-class, not single-pool, per the §2.3 Household/Student update.** A parent with kids in two classes should land on a household-level view (all active pools, aggregated amount due, aggregated savings) with each pool as a card, not be dropped into one class's screen with no way to see the other. This is a direct consequence of adding `Household`/`Student` to the data model — the UI in the original doesn't yet reflect it.
>
> **Found while reconciling this against §8.4's payment model**: an aggregated "amount due" view creates an expectation of paying it in one action — but §8.4's Stripe Connect destination charges route to *one* organizer's account per charge, and two different classes almost always have two different organizers. A single "Pay $47.60" button spanning both would need to be two separate Stripe charges under the hood anyway. Resolution: the household view **shows** one combined total for a quick read of what's owed, but **paying** stays one pool (one organizer) at a time — tapping "Pay" from the combined view just walks the parent through each outstanding pool's checkout in sequence, it doesn't imply a single transaction.

> 🔧 **PM UPDATE — Admin console needs one more row: organizer reassignment / escalation queue**, surfacing the inactive-organizer-with-pending-money case from the §2.1 update, plus a "reported class" queue feeding the trust/safety update in §14.

---

## 13. Data Model and State Machines

### 13.1 Core entities — original list
`User, ParentProfile, School, SchoolYear, Classroom, Membership, Pool, RequirementSource, Requirement, ProductSpecification, ParentInventory, Contribution, Allocation, ResidualDemand, ProductOffer, PurchasePlan, PurchasePlanLine, Payment, Order, OrderLine, DistributionBatch, DistributionItem, Notification, AuditEvent`

Later: `Supplier, AffiliateMerchant, SchoolSubscription, ClassReserve, UsedItemListing, Transfer, Dispute`

> 🔧 **PM UPDATE — additions needed for V1, not later, because they're referenced by other V1-required flows in this same document:**
> - **`Household`** and **`Student`** — required by §2.3/§12 for multi-child parents; `Membership` should reference `Student`, not just `ParentProfile`, so a child's participation is tracked per class even when the paying parent varies.
> - **`Invite`** — §16.1's funnel ("Invite created → Invite opened → Parent signed up") has no entity to attach those events to today; needs id, class/pool ref, channel, created-by, opened-at, converted-at.
> - **`ClassReserve` moved from "Later" to V1** — §9.4 and §10's year-end reuse both depend on it in the V1 flow (§17.1, step 41: "Unused extras move to Class Reserve"), so it can't be deferred without contradicting the V1 build order already in the original doc. Needs a `custodianLocation` field per the §9.4 physical-custody update, and can be scoped to either a `Classroom` or a `School` (for the stranded-reserve donate-up case).
> - **`Transfer` moved from "Later" to V1** — needed for the §9.4 stranded-reserve rule (moving reserve from a graduating class up to school-level, or across to next year's incoming class); the original only lists it as a later entity for the used-item marketplace, but the mechanism is identical and needed sooner.
> - **`OrganizerStripeAccount`** (or equivalent) to back the §8.4 Stripe Connect decision — tracks the organizer's connected account id/status per class, separate from `Payment`.
> - **`School.approvedEmailDomains`** — found missing in this pass: §14's Payment Unlock Gate names "organizer's email matches an approved school domain" as one of three ways to unlock payments, but no field anywhere holds what a school's approved domain *is*. Needs a list field on `School` (e.g. `["lincolnelementary.edu"]`), seeded by whoever creates the `School` record or curated by ClassPool Admin — without it, that gate condition has nothing to check against and silently falls through to "never satisfied by domain," pushing every class onto teacher-verification or manual admin approval by default.

### 13.2 Requirement state machine
`EXTRACTED → NEEDS_REVIEW → CONFIRMED → POOLING → LOCKED → PURCHASING → FULFILLED → CLOSED`
After purchasing begins, no silent changes — organizer edits create an explicit revision/change event.

### 13.3 Pool state machine
`DRAFT → OPEN_FOR_INVENTORY → OPEN_FOR_CONTRIBUTIONS → RECONCILING → PURCHASE_PROPOSED → PAYMENT_OPEN → ORDERED → DISTRIBUTING → COMPLETED`

> 🔧 **PM UPDATE — these are two separate state machines and the original never says how they line up, which is exactly the kind of thing that reads fine in a spec and then causes a real "wait, which state are we actually in?" bug once two engineers build against it independently.** A `Pool` holds many `Requirement`s, each progressing on its own — so they can't be identical machines, but they do constrain each other and that constraint should be explicit:
> - A `Pool` can't leave `DRAFT` until every `Requirement` in it is at least `CONFIRMED` (§3.3 — nothing is financially actionable pre-confirmation, which only makes sense if the pool can't be actionable pre-confirmation either).
> - `Requirement.POOLING` spans `Pool.OPEN_FOR_INVENTORY` through `RECONCILING`.
> - `Requirement` moves to `LOCKED` exactly when its `Pool` enters `PURCHASE_PROPOSED` — this is what "after purchasing begins, no silent changes" (above) actually anchors to.
> - `Requirement.PURCHASING → FULFILLED` tracks `Pool.ORDERED → DISTRIBUTING`; `Requirement.CLOSED` when `Pool.COMPLETED`.

> 🔧 **PM UPDATE — no state for a parent joining after `OPEN_FOR_CONTRIBUTIONS` has closed (the "late joiner" gap).** Common case: a family enrolls mid-term. Add an explicit rule rather than leaving it undefined: a `Membership` created after a pool leaves `OPEN_FOR_CONTRIBUTIONS` skips reuse/exchange entirely and is billed at the locked bulk-optimized per-unit price for a `LATE_JOIN` order line against the same `PurchasePlan` (or against `ClassReserve` first, if available) — it does not reopen reconciliation for everyone else.
>
> **Gap found within this fix**: it assumes an active `Pool` with a live `PurchasePlan` to attach to — but a family can just as easily join after the pool has already reached `COMPLETED` (e.g. enrolling over the summer, before next year's pool has even been created). There's no `PurchasePlan` to bill against at that point. Rule for that case: the new `Membership` is simply queued against `ClassReserve` (draws down whatever's already banked, per §9.4) and otherwise waits — no purchase is triggered on their behalf until the next `Pool` is created, at which point they're a normal on-time member of it, not a late joiner.

---

## 14. Security, Privacy, Safety and Abuse Prevention

**Child privacy**: parent-facing only, no child accounts, no child chat, no child location, no public student directory, no birthdates, first name/initial only where operationally necessary, no selling child-level behavioral data.

**Security baseline**: HTTPS everywhere, encryption at rest, RBAC, strict class/tenant authorization, signed upload URLs, malware scanning on uploads, rate limits, audit logs on sensitive/admin actions, managed payment provider, secrets manager, DB backups, MFA for platform admins, short-lived tokens, dependency/security scanning in CI, observability.

**Critical authorization test** (keep verbatim — this is the right bar): *Changing a class, pool, membership or requirement ID in an API request must never allow a parent from Class A to read or modify Class B.*

**Abuse/fraud table** (original): fake class → organizer verification/school-domain validation; fake contribution → don't count pledged as guaranteed; payment fraud → processor risk tools + webhook verification; fake supplier → approved catalog only; harassment → no open messaging, minimize identity exposure.

> 🔧 **PM UPDATE — "fake class" is under-weighted for a product whose entire premise is collecting money from strangers based on a claimed class.** The mitigation listed ("organizer verification; optional school-domain validation") is optional in the original — it can't be optional once §8.4's Stripe Connect payments are live. V1 needs a concrete **Payment Unlock Gate**: a class cannot move into `PAYMENT_OPEN` until at least one of — (a) organizer's email matches an approved school domain, (b) a teacher has verified the requirement list (§3.3), or (c) ClassPool Admin manually approves. Below that gate, everything (inventory, exchange, planning) still works — money just doesn't move. Add a persistent "Report this class" action visible to every parent, feeding the admin escalation queue from the §12 update.

> 🔧 **PM UPDATE — student-list privacy compliance isn't mentioned.** Even with no child accounts, an uploaded class roster/requirement list may originate from school records (FERPA-covered "education records" in the US, or local equivalents). Add an explicit organizer attestation at upload time ("I'm authorized to share this list") and keep retention/deletion policy for uploaded source documents in scope for V1 legal review — not a blocker to building, but a blocker to launch.

> 🔧 **PM UPDATE — accessibility isn't mentioned anywhere in the original.** Public schools/PTAs are a core distribution channel (§16.3, §19.2) and many will expect WCAG-level accessibility as a matter of course. Baseline: **WCAG 2.1 AA** for the PWA — keyboard navigation, screen-reader labels, color contrast, especially on the inventory stepper (§4) and payment flow (§8), both of which are original custom UI, not third-party widgets.

---

## 15. Technical Architecture and AI Boundaries

```
ClassPool PWA (Next.js)
        |
   HTTPS REST API
        |
Java / Spring Boot
        |
  +----------+----------+
  |          |          |
PostgreSQL  Redis       S3
  |
background jobs
  |
extraction / matching / notifications / pricing
```

| Layer | Choice |
|---|---|
| Frontend | Next.js + TypeScript, mobile-first PWA |
| Backend | Java 21+, Spring Boot |
| Database | PostgreSQL |
| Cache/jobs | Redis where justified |
| Object storage | AWS S3 |
| Auth | Managed provider; Google/Apple/email magic link |
| Payments | Stripe (Connect, per §8.4 update); Razorpay/UPI later |
| Email | AWS SES or transactional provider |
| Push | Web Push |
| Hosting | AWS backend; Vercel or AWS frontend initially |
| Observability | Structured logs, metrics, error tracking, traces |

**AI boundary** — this is the correct architectural spine of the product and needs no change:

```
PDF/image/message → document understanding → requirement extraction
  → structured JSON → schema validation → human approval
  → deterministic business logic
```

| AI may do | AI must not do |
|---|---|
| Extract names, quantities, brands, restrictions, source evidence | Calculate bills or payment amounts |
| Classify likely strictness with human confirmation | Allocate scarce items on opaque reasoning |
| Summarize ambiguous text for organizer review | Change locked pool state |
| Suggest product matches for review | Authorize substitutions violating organizer rules |

---

## 16. Analytics, Viral Loops and Monetization

**Funnel**: Invite created → opened → parent signed up → inventory started → completed → contribution offered → received → proposal viewed → payment completed → order delivered → pool completed → next pool joined.

**North-star**: $ of required purchases avoided (primary). Secondary: % demand via reuse, % via community contribution, savings per family, participation rate, repeat-class rate, pool completion rate, organizer time saved.

**Viral loop**: end every pool with a shareable result — *"Grade 1 saved $1,118 and reused 397 items with ClassPool."* → start another class / invite another teacher / share with PTA / create ClassPool for entire school.

**Monetization sequence**: V1 affiliate revenue on residual purchases where allowed → V2 supplier referral/commission → V3 school/PTA premium subscription → V4 transaction fee on used-item marketplace → later sponsored offers with strict transparency, no child-targeted profiling. Parent participation stays free.

---

## 17. V1 Scope, Exclusions and Build Order

**V1 flow** (kept as-is, it's correct and complete as an ordering): organizer creates class → invite/QR → parents join → list uploaded/entered → (AI extraction once stable) → organizer verifies → aggregate demand → parents mark owned → offer extras → matched to demand → organizer confirms contributions → residual demand calculated → bulk optimizer plan → organizer selects plan → household cost calculated → parents pay → organizer records purchase → distribution allocation generated → parents receive → pool completed → savings shown → unused extras → Class Reserve → next pool.

**Explicit exclusions**: native iOS/Android, child accounts, parent social chat, delivery fleet, warehouse, own product catalog, used-item marketplace, school ERP, gradebook/SIS, social feed, autonomous AI purchasing, school transportation, local-service marketplace.

**Build order (vertical slices)**: (1) PWA shell + auth → install & sign in. (2) Schools/classes/memberships → organizer creates class, parent joins. (3) Manual requirements → complete a list without AI. (4) Household inventory. (5) Contribution pool. (6) Allocation engine. (7) Residual-demand engine. (8) Bulk optimizer. (9) Payment allocation. (10) Ordering/distribution → one real pool end-to-end. (11) AI ingestion. (12) Notifications/analytics/polish.

> 🔧 **PM UPDATE — the money-flow, trust-gate, and household/student model decisions above are not "phase 12 polish"; they change what phases 2 and 9 actually build.** Recommend folding them into the existing build order rather than treating them as an add-on pass:
> - Phase 2 (Schools/classes/memberships): build `Household`/`Student` from the start — retrofitting it after `Membership` already points at `ParentProfile` directly is expensive.
> - Phase 9 (Payment allocation): build Stripe Connect destination charges and the Payment Unlock Gate together — a payments phase that has to be rebuilt for Connect once fraud review shows up in phase 12 is wasted work.
> Nothing else in the build order changes.

---

## 18. First Real-World Validation Gate

Before building every school feature, run the economics on one real class list (~20-30 products, ~24 students): simulate household inventory + surplus contributions, collect real retailer pack sizes/prices, compute independent-shopping baseline vs. ClassPool-optimized cost, measure % and $ savings, waste reduction, organizer effort.

**Go/no-go**: 4-7% savings → question the business. 20-40%+ savings with less waste and less organizer effort → strong signal.

**First technical milestone**: from a phone, an organizer creates a class, shares a link, a parent joins, both install to Home Screen, both see the same live class pool.

> 🔧 **PM UPDATE — the validation gate should also sanity-check the §8 payment-flow decision, not just the optimizer math.** Specifically: confirm a parent will actually complete a Stripe payment to an *individual organizer's* connected account (not a "ClassPool" merchant name) without it reading as suspicious. That's a trust question the pack-size math in this section doesn't cover, and it's cheap to test in the same real-class pilot.

---

## 19. Long-Term Moat and Expansion

Moat is the structured fulfillment graph and data from completed pools, not the front-end checklist: `Requirement → Compatibility → Household inventory → Community surplus → Allocation → Residual demand → Pack optimization → Retail/supplier offers → Distribution`.

**Learning flywheel**: reuse rates by item/grade, typical household surplus, best pack configurations, actual consumption/reserve needs, retailer economics, recurring requirements, seasonality. Predictive framing example: *"Based on similar Grade 1 classes, about 38% of this list may be fulfilled without new purchases."*

**Expansion path**: master classroom supplies → books/projects/uniforms/sports → school/PTA admin & reporting → supplier partnerships → used-item exchange selectively → additional countries with local payments/merchants/school workflows.

---

## 20. Executive Build Summary (updated)

| Decision | Recommendation |
|---|---|
| Initial surface | Mobile-first installable PWA |
| Core user / customer | **Parent** (payer, daily user) + parent-volunteer Class Organizer; Teacher verifies only, never required, never handles money |
| Core value | Reuse first, then pooled exchange, then residual purchase optimization |
| Critical engine | Deterministic allocation + residual-demand + pack optimizer |
| AI role | Unstructured requirement extraction with evidence + human approval; manual entry always available in parallel |
| Payments (V1) | Stripe **Connect**, destination charges to organizer's own account; ClassPool never holds a balance; organizer purchases externally |
| Payment safety gate | Class can't reach `PAYMENT_OPEN` without school-domain match, teacher verification, or admin approval |
| Data model additions | `Household`, `Student`, `Invite`, `ClassReserve` (moved to V1), `OrganizerStripeAccount` |
| Primary metric | Dollar value of purchases avoided |
| First launch proof | Complete one real class pool end-to-end with meaningful savings, and a parent completing payment to an individual organizer without hesitation |
| Do not build first | Native apps, child accounts, chat, warehouse, marketplace, school ERP |

---

## 21. Gap Log (summary of this revision)

| # | Gap | Where fixed |
|---|---|---|
| 1 | Organizer isn't modeled as a role on Parent; no self-serve class creation stated | §2.1 |
| 2 | No organizer succession / inactive-organizer handling while money is pending | §2.1, §12 |
| 3 | No notification consent/channel-priority model | §2.2, §11 |
| 4 | No Household/Student entity → no multi-child, multi-class parent view | §2.3, §12, §13.1 |
| 5 | Manual list entry reads as pre-AI placeholder, not permanent path | §3.2 |
| 6 | Contribution lifecycle has no return path for Lend items | §5 |
| 7 | Payment model contradicts itself (Stripe collects vs. organizer purchases externally) — biggest gap | §8.4, §15, §20 |
| 8 | No under-collection risk handling before organizer purchases | §8.4 |
| 9 | No refund/cancellation trigger rules | §8.4 |
| 10 | Post-purchase substitution has no defined billing-delta workflow | §9 |
| 11 | No late-joiner state/billing rule | §13.3 |
| 12 | `ClassReserve` listed as "later" while V1 flow depends on it | §13.1, §17 |
| 13 | No `Invite` entity despite funnel analytics needing one | §13.1, §16.1 |
| 14 | "Fake class" fraud control is optional despite gating real payments | §14 |
| 15 | No FERPA/student-data-source compliance note | §14 |
| 16 | No accessibility standard | §14 |
| 17 | No physical custody model for Class Reserve, or rule for stranded reserve at year-end/graduation | §9.4 |
| 18 | No school/class deduplication workflow — direct threat to pooling network effect | §2.3 |
| 19 | Self-reported inventory has no verification (documented as accepted risk, not a fix) | §4 |
| 20 | Substitution/equivalence rules for "equivalent_allowed" items have no authoring workflow | §6 |
| 21 | Physical distribution labor (pack-splitting, per-household counting) undesigned; organizer-burnout risk to viral loop | §9 |
| 22 | Stripe-only checkout excludes families with no card/digital wallet, with no fallback | §8.4 |
| 23 | §5, §7, §9, §11 were cited as numbered subsections throughout but never actually had those headings — every such cross-reference was unresolvable | §5, §7, §9, §11 |
| 24 | Payment Unlock Gate's "school-domain match" condition (§14) has no backing field on `School` | §13.1 |
| 25 | Requirement and Pool state machines have no stated relationship to each other | §13.3 |
| 26 | Late-joiner rule assumes an open pool with a live `PurchasePlan`; breaks if the pool is already `COMPLETED` | §13.3 |
| 27 | Stranded-reserve rule's "hand to next year's class" option assumes that class already exists, which it usually won't | §9.4 |
| 28 | Substitution "absorb silently, fold into next pool" breaks for a class's last-ever pool (no next pool to fold into) | §9.1 |
| 29 | Combined multi-child household payment view implies one payment, but Stripe Connect requires one charge per organizer | §12 |
| 30 | "V1 affiliate revenue" (§16.4) has no corresponding affiliate-link mechanism in the V1 build order | §7.4 |
| 31 | Lend-item return states added without re-flagging that Lend itself is scoped "later," not V1 | §5.4 |
| 32 | Under-collection payment threshold didn't say who sets it — needed to rule out organizer self-lowering it | §8.4 |

---

## 22. Worked End-to-End Example (Pens, 10 Families)

A concrete run through every phase of the V1 flow (§17.1), with every gap fix above shown in context rather than as an abstract rule. Numbers: 10 families, 2 pens/student requirement, only a 20-pack available on the market.

**Phase 0 — Class created.** Priya (a parent) taps "Create a class." Typing the school name fuzzy-matches existing classes first (§2.3's dedup check) — no match, so "Lincoln Elementary / Ms. Smith / Grade 1" is created and Priya becomes `Membership.role = ORGANIZER` on it, not a separate account type (§2.1). Join link + QR generated.

**Phase 1 — Parents join.** 10 families join via the link, auth with Google/Apple/magic-link. Each lands under `Household → Student → Membership` (§2.3) — a parent with two kids in the class just gets two `Student` rows under one `Household`.

**Phase 2 — Requirement list verified.** Priya uploads a photo of the list. AI extracts *"Pens — qty 2/student — brand: any — strictness: `equivalent_allowed` — confidence: 0.94"* (§3.2). A low-confidence item would instead force manual entry, never a silent guess (§3.2 update). Priya confirms it. Requirement → `CONFIRMED`. Aggregate class demand = 2 × 10 = **20 pens**.

**Phase 3 — Payment gate check.** Before this pool can ever collect money, the system checks organizer school-domain match / teacher verification / admin approval (§14 update). Ms. Smith taps "Verify" — gate satisfied now, before any money moves. Inventory and exchange work regardless of this gate; only payment collection is blocked without it.

**Phase 4 — Household inventory.** Each family runs the quick stepper (§4): *"Pens needed: 2 — how many do you already have?"* Class collectively already owns **5**. A family that never responds by deadline defaults to "0 owned" rather than blocking the other 9 (§4 update, adjacent).

**Phase 5 — Exchange pool.** Families offer extras (default Donate/Give). 3 pens are pledged and physically handed to Priya, who marks them `RECEIVED` (§5.4). One pledge of 5 only delivers 2 — only those 2 move to `RECEIVED → ALLOCATED`; the other 3 stay `PLEDGED` and don't reduce demand (§5.4, pledged-vs-confirmed rule). Confirmed surplus = **3**.

**Phase 6 — Residual demand.**
```
TotalRequired (20) − Owned&Retained (5) − ConfirmedSurplus (3) − ClassReserve (0) = 12
```
Tagged per household as self-fulfilled / pool-fulfilled / purchase-required — works out to 6 of the 10 families needing pens purchased, 12 pens between them (§6).

**Phase 7 — Bulk optimizer.** Only a 20-pack exists on the market; need ≥ 12 → buy one. Proposal: *1 × 20-pack, covers 12 needed, 8 to reserve* (§7). Priya reviews and approves.

**Phase 8 — Billing and payment.** The pack's cost is split only across the 6 families who actually need pens, proportional to units needed (~$0.71/pen at $8.49/pack) — the 8 leftover pens aren't billed to anyone separately; buying the whole pack was already the cheapest way to cover 12 (§7.1's waste-penalty logic). Each of the 6 pays via **Stripe Connect destination charge straight into Priya's own connected account** — never a ClassPool-held balance (§8.4) — with an explicit *"you're paying Priya, not ClassPool"* disclosure. At the deadline, 5 of 6 have paid; Priya sees a risk banner (*"$0.71 outstanding — Family F"*) and can proceed past the 90% threshold with an explicit acknowledgment, or wait (§8.4 update). A withdrawal before purchase gets a full refund out of Priya's Stripe balance (§8.4 update).

**Phase 9 — Purchase.** Because payment happens before purchase in the pool state machine (§13.3), the parents' money is already in Priya's account by the time she buys — she isn't fronting her own cash. If her priced pack is out of stock, she buys an alternate and logs a substitution; a delta ≤10% of the line is absorbed silently (unless this is the class's last-ever pool, in which case even a small delta triggers a top-up rather than having nowhere to fold into), a larger one prompts her to eat it or request a top-up (§9.1 update).

**Phase 10 — Distribution.** The app generates a **per-household pick list** straight from the allocation (§9.3 update): *"Family A: 1 pen. Family B: 2 pens…"* summing to 12. Priya counts out exactly per the list, bags/labels for those 6 families, distributes via classroom desks or take-home bags, marks each Delivered. The **8 leftover pens go to Class Reserve**, physically kept in Ms. Smith's classroom cabinet — not Priya's home, since the organizer role can turn over mid-year while the classroom can't (§9.4 update) — logged with a `custodianLocation` note. A family enrolling after this pool closed skips reuse/exchange, is billed at the locked $0.71/pen rate, but the app checks Class Reserve first — so a late joiner needing 1–2 pens gets them free from the 8 already banked, no new purchase triggered (§13.3 update).

**Phase 11 — Pool completed.** Shareable savings summary shown (§16.3). Priya's prompted to start the next pool; that pool's residual-demand calculation checks Class Reserve first, so the 8 banked pens reduce the next purchase automatically (§9.4). If this were the graduating class's last pool instead, Priya would be prompted to donate the reserve up to the school or hand it to next year's incoming class rather than let it strand (§9.4 update). If Priya had gone unresponsive with parent money already collected, any family could flag it for admin escalation/reassignment rather than the pool just stalling (§2.1 update).

---

*Original source: ClassPool Full Product & Engineering Blueprint (uploaded PDF). This revision: PM gap-analysis pass, 2026-09-04.*
