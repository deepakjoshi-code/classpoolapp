package app.classpool.api.domain;

/**
 * Matches the notification.channel check constraint in the V1 migration. Every notification this
 * codebase creates uses {@code PUSH} — conceptually "in-app", the only channel actually delivered
 * in this environment (no VAPID keys/Web Push subscription registration exist here, and no email
 * provider beyond {@code LoggingEmailSender} — see apps/api/README.md's "Notifications and
 * savings summary (Phase 12)" notes). {@code EMAIL}/{@code SMS} are laid down because the check
 * constraint allows them, for a later phase's real fan-out to use.
 */
public enum NotificationChannel {
    PUSH,
    EMAIL,
    SMS
}
