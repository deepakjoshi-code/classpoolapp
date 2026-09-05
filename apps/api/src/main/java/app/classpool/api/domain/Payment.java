package app.classpool.api.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One household's bill for a pool's residual purchase demand (PRD §8.1-8.3), created once by
 * {@code PaymentService.generatePayments} and mutated forward through {@link PaymentState}'s
 * gates. Maps directly onto the V1 migration's already-present {@code payment} table — no schema
 * changes needed for this phase.
 *
 * <p><b>Flagged schema/contract gap — {@code method}.</b> The contract's {@code Payment.method} is
 * {@code nullable: true} ("null until the household pays"), but the V1 migration's {@code
 * payment.method} column is {@code not null} (with a check constraint over exactly {@link
 * PaymentMethod}'s four values — no fifth "unset" sentinel is available without a migration, out
 * of this task's scope). This entity stores {@link PaymentMethod#CARD} as an arbitrary placeholder
 * at construction time to satisfy the {@code not null} constraint; {@code
 * PaymentService}'s response-building code is what actually honors the contract's nullability,
 * suppressing {@code method} back to {@code null} whenever {@code state == PENDING} (the one state
 * where the stored value is a placeholder, never real information) — every other state
 * ({@code PENDING_CASH} onward) always holds a real, organizer/household-supplied value written by
 * one of this entity's mark* methods before the state changes, so nothing past {@code PENDING} is
 * ever a placeholder. Flagged here rather than silently worked around, same boundary as the
 * Contribution {@code studentId} gap in apps/api/README.md's Phase 5 notes.
 */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "pool_id", nullable = false)
    private UUID poolId;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "amount_cents", nullable = false)
    private int amountCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentState state;

    @Column(name = "stripe_payment_intent_id")
    private String stripePaymentIntentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
    }

    public Payment(UUID poolId, UUID householdId, int amountCents) {
        this.poolId = poolId;
        this.householdId = householdId;
        this.amountCents = amountCents;
        this.method = PaymentMethod.CARD; // placeholder — see class Javadoc
        this.state = PaymentState.PENDING;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    /** {@code payMyPayment} (contract) — {@code PENDING -> PAID} via a real Stripe destination
     *  charge (stubbed in V1, see {@link StripeGateway}). Callers must check {@link #getState()}
     *  is {@code PENDING} first. */
    public void markPaid(PaymentMethod method, String stripePaymentIntentId) {
        this.method = method;
        this.state = PaymentState.PAID;
        this.stripePaymentIntentId = stripePaymentIntentId;
    }

    /** {@code markPaymentCashPending} (contract) — {@code PENDING -> PENDING_CASH}. From this
     *  point {@code method} holds real information (CASH), not the construction-time placeholder —
     *  see class Javadoc. */
    public void markCashPending() {
        this.method = PaymentMethod.CASH;
        this.state = PaymentState.PENDING_CASH;
    }

    /** {@code markPaymentCashReceived} (contract) — {@code PENDING_CASH -> PAID_CASH_RECEIVED}. */
    public void markCashReceived() {
        this.state = PaymentState.PAID_CASH_RECEIVED;
    }

    /** {@code refundPayment} (contract) — {@code PAID}/{@code PAID_CASH_RECEIVED -> REFUNDED}. */
    public void markRefunded() {
        this.state = PaymentState.REFUNDED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPoolId() {
        return poolId;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    public int getAmountCents() {
        return amountCents;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public PaymentState getState() {
        return state;
    }

    public String getStripePaymentIntentId() {
        return stripePaymentIntentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
