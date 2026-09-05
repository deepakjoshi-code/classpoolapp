package app.classpool.api.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * An in-app event addressed to one {@link AppUser} (PRD §11.3), mapped directly onto the V1
 * migration's already-present {@code notification} table — no new migration for this phase (see
 * apps/api/README.md's "Notifications and savings summary (Phase 12)" notes).
 *
 * <p><b>{@code poolId}/{@code message} live inside {@link #payload}, not as their own columns.</b>
 * The V1 migration's {@code notification} table has exactly {@code user_id}/{@code type}/
 * {@code channel}/{@code payload}/{@code sent_at}/{@code read_at} — no dedicated {@code pool_id}
 * or {@code message} column — so both are written into this row's {@code payload} jsonb blob at
 * construction time and read back out by {@link #getPoolId()}/{@link #getMessage()}. This is the
 * same "the contract needs a field the table doesn't have a column for" shape as Phase 9's
 * {@code Payment.method}/Phase 10's {@code OrderLine.substitutionDeltaCents} gaps, just resolved
 * differently here: those compute a value live from other frozen columns, this one persists into
 * the one flexible column the table actually offers instead of adding a migration.
 *
 * <p>{@code channel} is always {@link NotificationChannel#PUSH} (in-app) and {@code sentAt} is
 * always set to "now" at construction — delivery is synchronous for an in-app list, unlike a real
 * push/email queue where sending happens later than creation. See {@code NotificationService}'s
 * Javadoc for the one write path every notification goes through.
 */
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Free-text column in the V1 migration (no check constraint) — {@link NotificationType}
     *  exists purely so Java call sites can't typo a type name; see its own Javadoc. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = new LinkedHashMap<>();

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Notification() {
    }

    public Notification(UUID userId, NotificationType type, UUID poolId, String message) {
        this.userId = userId;
        this.type = type;
        this.channel = NotificationChannel.PUSH;
        this.payload = new LinkedHashMap<>();
        this.payload.put("poolId", poolId == null ? null : poolId.toString());
        this.payload.put("message", message);
        this.sentAt = Instant.now();
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** Idempotent — a second call on an already-read notification is a no-op, matching the
     *  contract's own "calling again just returns it unchanged" wording. Callers don't need to
     *  check {@link #getReadAt()} themselves first. */
    public void markRead() {
        if (readAt == null) {
            readAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public NotificationType getType() {
        return type;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public UUID getPoolId() {
        Object raw = payload.get("poolId");
        return raw == null ? null : UUID.fromString(raw.toString());
    }

    public String getMessage() {
        Object raw = payload.get("message");
        return raw == null ? null : raw.toString();
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
