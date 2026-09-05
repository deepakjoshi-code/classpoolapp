package app.classpool.api.domain;

/**
 * Matches the contribution.mode check constraint in the V1 migration and PRD §5.1's "Offer
 * surplus" list. Phase 5 only drives {@code DONATE} (Give) — {@link ContributionService#offer}
 * rejects any other value with 400, since PRD §5.1 marks Lend/Sell as explicitly "later" — but the
 * full set is laid down here because the contract's {@code Contribution.mode} column check
 * constraint (and the later-phase state machine in PRD §5.4's PM update) already allow for them.
 */
public enum ContributionMode {
    DONATE,
    LEND,
    SELL,
    KEEP
}
