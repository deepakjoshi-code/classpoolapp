import type { Pool, Requirement } from "./api/types";

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
