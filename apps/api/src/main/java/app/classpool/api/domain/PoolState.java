package app.classpool.api.domain;

/**
 * Matches the pool.state check constraint in the V1 migration and PRD §13.3's pool state
 * machine. Phase 3 only drives DRAFT -&gt; OPEN_FOR_INVENTORY (via PoolService.confirm); every
 * later state is laid down here because the contract's Pool.state enum lists them, and later
 * phases advance a Pool into them.
 */
public enum PoolState {
    DRAFT,
    OPEN_FOR_INVENTORY,
    OPEN_FOR_CONTRIBUTIONS,
    RECONCILING,
    PURCHASE_PROPOSED,
    PAYMENT_OPEN,
    ORDERED,
    DISTRIBUTING,
    COMPLETED
}
