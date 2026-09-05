package app.classpool.api.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One organizer's Stripe Express Connect account, scoped to one classroom (PRD §8.4). Maps
 * directly onto the V1 migration's already-present {@code organizer_stripe_account} table — no
 * schema changes needed for this phase. The table's unique key is {@code (user_id, classroom_id)},
 * not {@code classroom_id} alone: onboarding is tracked per organizer, since that's who actually
 * completes Stripe's KYC flow, but {@code PaymentService.generatePayments}'s "is this classroom
 * ready to take payments" check looks for <em>any</em> {@code ACTIVE} account on the classroom —
 * the payout destination doesn't care which co-organizer set it up.
 *
 * <p>The V1 migration has no column for a hosted onboarding URL — {@code
 * StripeGateway.onboardingUrlFor} reconstructs it on every read from the stored {@code
 * stripeAccountId} alone (a real Stripe implementation would mint a fresh, short-lived Account
 * Link the same way, on every call) rather than persisting one, so there's nothing to keep in
 * sync.
 */
@Entity
@Table(name = "organizer_stripe_account")
public class OrganizerStripeAccount {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "classroom_id", nullable = false)
    private UUID classroomId;

    @Column(name = "stripe_account_id", nullable = false)
    private String stripeAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrganizerStripeAccountStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OrganizerStripeAccount() {
    }

    public OrganizerStripeAccount(UUID userId, UUID classroomId, String stripeAccountId) {
        this.userId = userId;
        this.classroomId = classroomId;
        this.stripeAccountId = stripeAccountId;
        this.status = OrganizerStripeAccountStatus.PENDING;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** The {@code .../stripe-onboarding/complete} action (contract) — {@code PENDING -> ACTIVE}.
     *  Callers must check {@link #getStatus()} is {@code PENDING} first, same division of labor as
     *  {@code PurchasePlan.approve}/{@code Contribution.markReceived}. */
    public void activate() {
        this.status = OrganizerStripeAccountStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getClassroomId() {
        return classroomId;
    }

    public String getStripeAccountId() {
        return stripeAccountId;
    }

    public OrganizerStripeAccountStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
