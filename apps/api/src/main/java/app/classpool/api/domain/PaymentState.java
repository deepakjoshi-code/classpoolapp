package app.classpool.api.domain;

/**
 * Matches the {@code payment.state} check constraint in the V1 migration and the contract's
 * {@code Payment.state} enum exactly (PRD §8.4 + its cash-fallback update). {@code
 * PaymentService} drives {@code PENDING -> PAID} (card/wallet, via {@link StripeGateway}), {@code
 * PENDING -> PENDING_CASH -> PAID_CASH_RECEIVED} (organizer-recorded cash), and {@code PAID}/{@code
 * PAID_CASH_RECEIVED -> REFUNDED} (pre-{@code ORDERED} only). {@code FAILED} and {@code
 * PARTIALLY_REFUNDED} are laid down for schema-completeness/later-phase use (a failed Stripe
 * charge callback, a partial refund policy) — nothing in this phase ever sets them.
 */
public enum PaymentState {
    PENDING,
    PAID,
    FAILED,
    REFUNDED,
    PARTIALLY_REFUNDED,
    PENDING_CASH,
    PAID_CASH_RECEIVED
}
