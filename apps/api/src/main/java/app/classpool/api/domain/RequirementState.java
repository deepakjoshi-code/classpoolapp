package app.classpool.api.domain;

/**
 * Matches the requirement.state check constraint in the V1 migration and PRD §13.2's requirement
 * state machine. Phase 3 only drives EXTRACTED/NEEDS_REVIEW -&gt; CONFIRMED (via
 * PoolService.confirm — PRD §13.3's stated mapping: a Pool can't leave DRAFT until every
 * Requirement in it is at least CONFIRMED); every later state is laid down here because the
 * contract's Requirement.state enum lists them, and later phases advance a Requirement into them.
 */
public enum RequirementState {
    EXTRACTED,
    NEEDS_REVIEW,
    CONFIRMED,
    POOLING,
    LOCKED,
    PURCHASING,
    FULFILLED,
    CLOSED
}
