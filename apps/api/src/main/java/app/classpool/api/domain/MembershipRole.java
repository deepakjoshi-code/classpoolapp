package app.classpool.api.domain;

/** Matches the membership.role check constraint in the V1 migration (PRD §2.1). */
public enum MembershipRole {
    PARENT,
    ORGANIZER,
    CO_ORGANIZER
}
