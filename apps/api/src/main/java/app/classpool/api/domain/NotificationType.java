package app.classpool.api.domain;

/**
 * PRD §11.3's full notification event list (matches the contract's {@code Notification.type}
 * enum exactly). The {@code notification.type} column itself is free text with no check
 * constraint in the V1 migration, so nothing in the database enforces this list — it exists so
 * Java call sites can't typo a type name, same purpose an enum backed by a check constraint would
 * serve elsewhere in this codebase.
 *
 * <p>Phase 12 only ever constructs three of these — {@link #PAYMENT_DUE} ({@code PaymentService
 * .generatePayments}), {@link #BUNDLE_READY} ({@code DistributionService.generateDistribution}),
 * and {@link #POOL_COMPLETED} ({@code PoolService.complete}) — every other value is laid down here
 * because the contract's enum lists it, for a later phase to start emitting. Same "full enum,
 * partial emission" pattern as {@link PoolState}/{@link RequirementState} (see their own Javadoc).
 */
public enum NotificationType {
    CLASS_INVITE,
    NEW_POOL,
    INVENTORY_COMPLETE,
    CONTRIBUTION_ALLOCATED,
    REUSE_PERIOD_ENDING,
    PAYMENT_DUE,
    PURCHASE_COMPLETED,
    BUNDLE_READY,
    POOL_COMPLETED,
    LEND_ITEM_DUE_BACK
}
