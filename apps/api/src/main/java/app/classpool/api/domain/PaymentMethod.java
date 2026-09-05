package app.classpool.api.domain;

/**
 * Matches the {@code payment.method} check constraint in the V1 migration and the contract's
 * {@code Payment.method} enum exactly. See {@link Payment}'s Javadoc for the one wrinkle: the
 * column is {@code not null} in the schema even though the contract marks the field {@code
 * nullable: true} ("null until the household pays") — {@link Payment} stores a placeholder here
 * while {@code state = PENDING} and {@code PaymentService} suppresses it back to {@code null} in
 * the API response for exactly that state, never persisting an actual null.
 */
public enum PaymentMethod {
    CARD,
    APPLE_PAY,
    GOOGLE_PAY,
    CASH
}
