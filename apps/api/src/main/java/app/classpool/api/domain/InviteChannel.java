package app.classpool.api.domain;

/** Matches the invite.channel check constraint in the V1 migration. */
public enum InviteChannel {
    EMAIL,
    SMS,
    LINK,
    QR
}
