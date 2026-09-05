package app.classpool.api.service;

import java.util.UUID;

/**
 * Outbound Stripe Connect boundary (PRD §8.4: "ClassPool never holds a balance — every parent
 * payment settles directly to [the organizer's] account", via destination charges). {@link
 * StubStripeGateway} is the only implementation wired up in this environment — there is no Stripe
 * API key available in this sandbox, and no guarantee of network access to stripe.com even if
 * there were one.
 *
 * <p>This is a real, load-bearing architectural boundary, not a convenience shim: a later phase
 * swaps in a real Stripe-SDK-backed implementation of this exact interface — e.g. wrapping {@code
 * com.stripe.model.Account}/{@code AccountLink}/{@code PaymentIntent}/{@code Refund} calls from
 * the official {@code stripe-java} SDK — and nothing in {@code PaymentService} or any controller
 * changes. Method shapes below are deliberately written to match what that real wrapper would
 * need, not what the stub happens to find convenient:
 *
 * <ul>
 *   <li>{@link #createExpressAccount} → {@code Account.create(...)} with {@code type: "express"},
 *       plus an initial {@code AccountLink.create(...)} for the hosted onboarding flow.
 *   <li>{@link #onboardingUrlFor} → a fresh {@code AccountLink.create(...)} call. Real Stripe
 *       Account Links are short-lived (they expire in minutes), so a real implementation
 *       necessarily re-mints one on every call — safe to call as often as needed, unlike {@link
 *       #createExpressAccount}, which must run at most once per {@code (organizer, classroom)}
 *       pair (see {@code PaymentService.startStripeOnboarding}'s idempotency).
 *   <li>{@link #checkAccountStatus} → {@code Account.retrieve(stripeAccountId)}, deriving
 *       {@code ACTIVE}/{@code RESTRICTED} from the real account's {@code charges_enabled}/{@code
 *       requirements} fields. Not called anywhere in V1 — {@code
 *       PaymentService.completeStripeOnboarding} advances the status from the frontend's own
 *       return-URL landing page, per the contract's own summary ("in production this transition is
 *       normally driven by a Stripe webhook, not a client call") — but laid down here since a
 *       later phase's webhook handler needs exactly this shape to reconcile status without trusting
 *       the client.
 *   <li>{@link #createDestinationCharge} → {@code PaymentIntent.create(...)} with {@code
 *       transfer_data[destination]: stripeAccountId}.
 *   <li>{@link #refund} → {@code Refund.create(...)}.
 * </ul>
 */
public interface StripeGateway {

    /** Creates a new Stripe Express account for this organizer/classroom pair, plus its first
     *  onboarding link. Must be called at most once per pair — see class Javadoc. */
    ExpressAccountResult createExpressAccount(UUID organizerUserId, UUID classroomId);

    /** A fresh hosted onboarding URL for an already-created account. Idempotent/side-effect-free
     *  to call repeatedly (see class Javadoc) — unlike {@link #createExpressAccount}. */
    String onboardingUrlFor(String stripeAccountId);

    /** Live status for an account, as Stripe itself would report it right now. The stub simply
     *  echoes {@code currentStatus} back — it never contacts a real Stripe account, so it has
     *  nothing else to report, and per this task's spec must never quietly "complete" onboarding
     *  on its own; only {@code PaymentService.completeStripeOnboarding} does that. */
    String checkAccountStatus(String stripeAccountId, String currentStatus);

    /** A Stripe Connect destination charge from the paying household straight to the organizer's
     *  account (PRD §8.4). Returns the resulting PaymentIntent id. */
    String createDestinationCharge(String stripeAccountId, int amountCents, UUID paymentId);

    /** A full refund of a previously-created charge. Returns the resulting Refund id. */
    String refund(String stripePaymentIntentId);

    /** @param stripeAccountId a fake {@code acct_...} id in {@link StubStripeGateway}, a real
     *                         Stripe Connect account id from a real implementation
     *  @param onboardingUrl   the hosted link to send the organizer to */
    record ExpressAccountResult(String stripeAccountId, String onboardingUrl) {
    }
}
