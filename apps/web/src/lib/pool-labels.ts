import type {
  AllocationStatus,
  Contribution,
  Payment,
  Pool,
  PurchasePlan,
  Requirement,
} from "./api/types";

/**
 * Plain-language strictness copy for parents/organizers (PRD §3.3: "Modes:
 * Exact item / Equivalent allowed / Generic"). The PRD only names the three
 * modes tersely — it doesn't spell out parent-facing copy for each — so the
 * label/hint text here is our own interpretation of what each mode means in
 * practice, written for a non-technical audience rather than the raw enum
 * name (see apps/web/README.md).
 */
export const STRICTNESS_OPTIONS: Array<{
  value: Requirement["strictness"];
  label: string;
  hint: string;
}> = [
  {
    value: "EXACT",
    label: "Must match exactly",
    hint: "Only this exact item — and brand, if you set one — is acceptable. No substitutes.",
  },
  {
    value: "EQUIVALENT_ALLOWED",
    label: "Any equivalent brand or type is fine",
    hint: "Families can supply anything that does the same job, even a different brand.",
  },
  {
    value: "GENERIC",
    label: "Any item that fits the description works",
    hint: "The loosest match — anything that reasonably fits what's described works.",
  },
];

export function strictnessLabel(value: Requirement["strictness"]): string {
  return STRICTNESS_OPTIONS.find((o) => o.value === value)?.label ?? value;
}

export const POOL_STATE_LABELS: Record<Pool["state"], string> = {
  DRAFT: "Building the supply list",
  OPEN_FOR_INVENTORY: "List confirmed — checking what families already have",
  OPEN_FOR_CONTRIBUTIONS: "Open for contributions",
  RECONCILING: "Reconciling",
  PURCHASE_PROPOSED: "Purchase proposed",
  PAYMENT_OPEN: "Payment open",
  ORDERED: "Ordered",
  DISTRIBUTING: "Distributing",
  COMPLETED: "Completed",
};

export function poolStateLabel(state: Pool["state"]): string {
  return POOL_STATE_LABELS[state] ?? state;
}

/**
 * "Shop Your Home First" completion copy (PRD §4.2: "After completion show
 * immediate value, for example: 'You already have $31 worth of your list.'").
 * This phase's data has no item prices, so rather than fabricate a dollar
 * figure, this adapts the same "immediate value" framing to an honest count
 * of checklist items (requirement × student rows) already fully covered by
 * what the household already owns — see apps/web/README.md for why item
 * count, not a touched/untouched flag, is the completion signal used here.
 */
export function inventoryCoverageMessage(
  coveredCount: number,
  totalCount: number
): string {
  if (totalCount === 0) return "";
  if (coveredCount === totalCount) {
    return `You already have all ${totalCount} item${totalCount === 1 ? "" : "s"} covered!`;
  }
  return `${coveredCount} of ${totalCount} item${totalCount === 1 ? "" : "s"} already covered.`;
}

/**
 * Plain-language status copy for a pledged/received contribution (PRD §5.4:
 * `PLEDGED → RECEIVED`). Deliberately worded so the two states read as
 * unambiguously different sentences, not just a color swap — a "state that's
 * PLEDGED vs RECEIVED should read unambiguously different" per the task's
 * accessibility bar, since color alone isn't a reliable signal (WCAG 2.1 AA
 * 1.4.1). Shared between the parent's own pledge view and the organizer's
 * confirmation list so the two surfaces never describe the same state two
 * different ways.
 */
export const CONTRIBUTION_STATE_LABELS: Record<Contribution["state"], string> = {
  PLEDGED: "Pledged — not yet received",
  RECEIVED: "Received — thank you!",
};

export function contributionStateLabel(state: Contribution["state"]): string {
  return CONTRIBUTION_STATE_LABELS[state] ?? state;
}

/**
 * States a pool passes through *before* `POST /pools/{poolId}/reconcile`
 * (PRD §6's allocation & residual-demand engine) has been run. Used to gate
 * both the one-way "work out what still needs buying" action (shown only
 * while the pool is still `OPEN_FOR_INVENTORY`, the one state the contract's
 * reconcile endpoint accepts) and the two read-only allocation views (shown
 * only once reconcile has produced something for them to show).
 */
const POOL_STATES_BEFORE_RECONCILING: ReadonlySet<Pool["state"]> = new Set([
  "DRAFT",
  "OPEN_FOR_INVENTORY",
  "OPEN_FOR_CONTRIBUTIONS",
]);

export function hasReconciled(state: Pool["state"]): boolean {
  return !POOL_STATES_BEFORE_RECONCILING.has(state);
}

/**
 * Plain-language status copy for one (requirement, student) allocation
 * outcome (PRD §6's engine — described here without its internal "residual
 * demand"/"allocation engine" names, which are jargon for an organizer or
 * parent reading this). Shared, identical wording between the organizer's
 * per-student breakdown and a household's own "my allocation" view — same
 * "one sentence, reused verbatim across surfaces" approach as
 * `CONTRIBUTION_STATE_LABELS` above, rather than two independently-worded
 * copies of the same fact that could drift apart.
 *
 * `purchaseNeeded` (typically `AllocationLine.purchaseRequiredQuantity`) lets
 * the `PURCHASE_REQUIRED` case name the actual shortfall — the only status
 * where a bare label would omit the number that makes the copy useful;
 * `SELF_FULFILLED`/`POOL_FULFILLED` need no such number, since nothing
 * further is being asked of the family either way.
 */
export const ALLOCATION_STATUS_LABELS: Record<AllocationStatus, string> = {
  SELF_FULFILLED: "Already has enough",
  POOL_FULFILLED: "Covered by donated supplies",
  PURCHASE_REQUIRED: "Will be part of the class purchase",
};

export function allocationStatusLabel(
  status: AllocationStatus,
  purchaseNeeded?: number
): string {
  if (status === "PURCHASE_REQUIRED" && typeof purchaseNeeded === "number") {
    return `Still needs ${purchaseNeeded} — will be part of the class purchase`;
  }
  return ALLOCATION_STATUS_LABELS[status] ?? status;
}

/**
 * States a pool passes through *before* a purchase plan exists for it (PRD
 * §7-8's bulk-pack purchase-plan engine, described here without that
 * internal name) — i.e. before `POST /pools/{poolId}/purchase-plan/generate`
 * has run. Same "or later" shape as `POOL_STATES_BEFORE_RECONCILING`/
 * `hasReconciled` above, just one transition further along: everything up to
 * and including `RECONCILING` has no plan yet, `PURCHASE_PROPOSED` is the
 * first state that does, and every state after that (`PAYMENT_OPEN`,
 * `ORDERED`, `DISTRIBUTING`, `COMPLETED` — the rest of `Pool["state"]`) keeps
 * one, so `PurchasePlanPanel` stays mounted through all of them rather than
 * disappearing once billing/ordering moves the pool state further along.
 */
const POOL_STATES_BEFORE_PURCHASE_PLAN: ReadonlySet<Pool["state"]> = new Set([
  "DRAFT",
  "OPEN_FOR_INVENTORY",
  "OPEN_FOR_CONTRIBUTIONS",
  "RECONCILING",
]);

export function hasPurchasePlan(state: Pool["state"]): boolean {
  return !POOL_STATES_BEFORE_PURCHASE_PLAN.has(state);
}

/**
 * Plain-language status copy for a `PurchasePlan.state` (PROPOSED ->
 * APPROVED). Same "one sentence, reused verbatim" approach as
 * `CONTRIBUTION_STATE_LABELS`/`ALLOCATION_STATUS_LABELS` above — there's only
 * one read surface for this today (`PurchasePlanPanel`), but the helper is
 * kept alongside the others rather than inlined so a second surface (e.g. a
 * future household-facing "what's happening with the purchase" view) reuses
 * identical wording rather than re-describing the same two states.
 */
export const PURCHASE_PLAN_STATE_LABELS: Record<PurchasePlan["state"], string> = {
  PROPOSED: "Waiting for your approval",
  APPROVED: "Approved",
};

export function purchasePlanStateLabel(state: PurchasePlan["state"]): string {
  return PURCHASE_PLAN_STATE_LABELS[state] ?? state;
}

/**
 * States a pool passes through *before* per-household `Payment`s exist for
 * it (PRD §8's Stripe Connect payment collection, following the approved
 * purchase plan) — i.e. before `POST /pools/{poolId}/payments/generate` has
 * run. Same "or later" shape as `POOL_STATES_BEFORE_PURCHASE_PLAN`/
 * `hasPurchasePlan` above, one transition further along: everything up to
 * and including `PURCHASE_PROPOSED` has no payments yet, `PAYMENT_OPEN` is
 * the first state that does, and every state after that (`ORDERED`,
 * `DISTRIBUTING`, `COMPLETED`) keeps them — `OrganizerPaymentsPanel` and
 * `PaymentsThresholdPanel` stay mounted through all of them rather than
 * disappearing once the pool moves on to ordering/distribution.
 */
const POOL_STATES_BEFORE_PAYMENTS: ReadonlySet<Pool["state"]> = new Set([
  "DRAFT",
  "OPEN_FOR_INVENTORY",
  "OPEN_FOR_CONTRIBUTIONS",
  "RECONCILING",
  "PURCHASE_PROPOSED",
]);

export function hasPayments(state: Pool["state"]): boolean {
  return !POOL_STATES_BEFORE_PAYMENTS.has(state);
}

/**
 * Plain-language status copy for a `Payment.state` (PRD §8.4 — Stripe card/
 * Apple Pay/Google Pay, plus the cash fallback and refund states). Shared,
 * identical wording between the organizer's per-household list
 * (`OrganizerPaymentsPanel`) and a household's own payment page — same
 * "one sentence, reused verbatim across surfaces" approach as
 * `CONTRIBUTION_STATE_LABELS`/`ALLOCATION_STATUS_LABELS`/
 * `PURCHASE_PLAN_STATE_LABELS` above, so raw enum values (`PENDING_CASH`,
 * `PAID_CASH_RECEIVED`, …) never reach either screen.
 */
export const PAYMENT_STATE_LABELS: Record<Payment["state"], string> = {
  PENDING: "Payment due",
  PAID: "Paid",
  FAILED: "Payment failed",
  REFUNDED: "Refunded",
  PARTIALLY_REFUNDED: "Partially refunded",
  PENDING_CASH: "Paying by cash/check — arrange with the organizer",
  PAID_CASH_RECEIVED: "Paid by cash — received",
};

export function paymentStateLabel(state: Payment["state"]): string {
  return PAYMENT_STATE_LABELS[state] ?? state;
}
