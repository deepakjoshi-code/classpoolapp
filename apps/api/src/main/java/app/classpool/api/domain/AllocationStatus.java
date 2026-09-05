package app.classpool.api.domain;

/**
 * Matches the {@code allocation_line.status} check constraint in the V3 migration and the
 * contract's {@code AllocationStatus} schema exactly (PRD §6). Derived per (requirement, student)
 * line by {@code AllocationService.reconcile}'s household-inventory-then-pool-then-purchase
 * waterfall: {@code SELF_FULFILLED} — the household's own recorded inventory covers it in full;
 * {@code POOL_FULFILLED} — fully covered once RECEIVED surplus contributions are counted (some of
 * the need came from the pool, not just the household); {@code PURCHASE_REQUIRED} — still short
 * after both, which is what feeds the class's {@link ResidualDemandLine}.
 */
public enum AllocationStatus {
    SELF_FULFILLED,
    POOL_FULFILLED,
    PURCHASE_REQUIRED
}
