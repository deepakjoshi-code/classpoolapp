package app.classpool.api.domain;

/**
 * Matches the {@code purchase_plan.state} check constraint in the V1 migration and the contract's
 * {@code PurchasePlan.state} enum exactly (PRD §7/§9), mirroring {@link AllocationStatus}'s style
 * of one enum per persisted state-machine column.
 */
public enum PurchasePlanState {
    PROPOSED,
    APPROVED
}
