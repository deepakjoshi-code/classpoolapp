package app.classpool.api.domain;

/**
 * Matches the contribution.state check constraint in the V1 migration. Phase 5 only drives
 * {@code PLEDGED -> RECEIVED} (PRD §5.4 — "purchasing calculations should distinguish promised
 * surplus from physically confirmed surplus"); every later state is laid down here because the
 * contract's Contribution.state enum (and PRD §5.4's PM-update Lend return-path states) already
 * list them, and later phases advance a Contribution into them.
 */
public enum ContributionState {
    PLEDGED,
    RECEIVED,
    ALLOCATED,
    DISTRIBUTED,
    RETURN_DUE,
    RETURNED,
    OVERDUE,
    LOST_OR_DAMAGED
}
