package app.classpool.api.domain;

/** Matches the requirement.strictness check constraint in the V1 migration (PRD §3.3). */
public enum RequirementStrictness {
    EXACT,
    EQUIVALENT_ALLOWED,
    GENERIC
}
