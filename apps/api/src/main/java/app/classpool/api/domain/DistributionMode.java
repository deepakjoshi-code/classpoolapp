package app.classpool.api.domain;

/**
 * Matches the {@code distribution_batch.mode} check constraint in the V1 migration and the
 * contract's {@code generateDistribution} request/{@code DistributionSummary.mode} enum exactly
 * (PRD §9.2/§9.3) — how the organizer physically hands items back to households.
 */
public enum DistributionMode {
    CLASSROOM_DESK,
    LOBBY_PICKUP,
    HOUSEHOLD_BAG
}
