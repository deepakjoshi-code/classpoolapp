package app.classpool.api.domain;

/**
 * Matches the {@code organizer_stripe_account.status} check constraint in the V1 migration and
 * the contract's {@code OrganizerStripeAccount.status} enum exactly (PRD §8.4's lightweight
 * Stripe Express onboarding). {@code RESTRICTED} is laid down here even though nothing in this
 * phase ever sets it — a real Stripe-SDK-backed {@link app.classpool.api.service.StripeGateway}
 * implementation would drive it via webhook (an account that later fails Stripe's own requirements
 * review), the same "lay down the full enum, drive only part of it this phase" instinct as
 * {@code ContributionState}/{@code PaymentState}.
 */
public enum OrganizerStripeAccountStatus {
    PENDING,
    ACTIVE,
    RESTRICTED
}
