package app.classpool.api.domain;

/**
 * Matches the contract's {@code OrderLine.substitutionResolution} enum exactly (PRD §9.1 update).
 * <b>Not a persisted column</b> — unlike every other status enum in this codebase ({@link
 * PaymentState}, {@link AllocationStatus}, ...), the V1 migration's {@code order_line} table has no
 * {@code substitution_delta_cents}/{@code substitution_delta_resolution} columns at all, only
 * {@code actual_description}/{@code actual_cost_cents}. This is the same kind of flagged
 * schema/contract gap as {@code Payment.method} (see apps/api/README.md's Phase 9 notes):
 * {@code OrderService} computes {@code delta}/this resolution live, on every read, from {@code
 * actualCostCents - <the PurchasePlanLine's frozen totalCostCents>} rather than persisting it —
 * there is nowhere to persist it without a migration, out of this task's scope, and the inputs
 * (both frozen once purchased) never change, so live computation is exactly as stable as a stored
 * value would be.
 */
public enum SubstitutionResolution {
    ABSORBED,
    TOP_UP_CHARGED
}
