package app.classpool.api.domain;

/**
 * Matches the {@code requirement_source.source_type} check constraint in the V1 migration in full
 * (PRD §3.1/§3.2). Phase 11 only ever creates {@link #PASTED_EMAIL}, {@link #PASTED_PORTAL}, and
 * {@link #PASTED_MESSAGE} rows (the contract's {@code importRequirementsFromText} only accepts
 * those three) — {@link #PDF}, {@link #PHOTO}, {@link #SCREENSHOT}, and {@link #WORD_DOC} need
 * object storage (no S3 credentials in this sandbox, see README) and {@link #MANUAL} is Phase 3's
 * manual-entry path, which this phase does not retrofit (see {@code RequirementService}'s Javadoc).
 * All eight values are still laid down here, same "match the full check constraint even if V1 only
 * drives a subset" instinct as {@code ContributionMode}/{@code PoolState}.
 */
public enum RequirementSourceType {
    PDF,
    PHOTO,
    SCREENSHOT,
    WORD_DOC,
    PASTED_EMAIL,
    PASTED_PORTAL,
    PASTED_MESSAGE,
    MANUAL
}
