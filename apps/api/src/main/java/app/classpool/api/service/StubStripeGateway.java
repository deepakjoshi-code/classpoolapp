package app.classpool.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Never makes a network call — there is no Stripe API key available in this environment (and no
 * guarantee of network access to stripe.com even if there were one). Deterministic, always-succeed
 * behavior standing in for the real thing; every fake id is prefixed the way a real Stripe id
 * would be ({@code acct_}/{@code pi_}/{@code re_}) purely so logs/responses read naturally, not
 * because anything parses the prefix. Swap for a real {@code stripe-java}-backed {@link
 * StripeGateway} when API access is available — nothing in {@code PaymentService} or any
 * controller needs to change; see {@link StripeGateway}'s Javadoc for exactly what each method
 * would call in a real implementation.
 */
@Component
public class StubStripeGateway implements StripeGateway {

    private static final Logger log = LoggerFactory.getLogger(StubStripeGateway.class);

    @Override
    public ExpressAccountResult createExpressAccount(UUID organizerUserId, UUID classroomId) {
        String accountId = "acct_stub_" + randomSuffix();
        log.info("[StubStripeGateway] Would create Express account for organizer {} / classroom {}: {}",
                organizerUserId, classroomId, accountId);
        return new ExpressAccountResult(accountId, onboardingUrlFor(accountId));
    }

    @Override
    public String onboardingUrlFor(String stripeAccountId) {
        return "https://connect.stripe.test/setup/stub/" + stripeAccountId;
    }

    @Override
    public String checkAccountStatus(String stripeAccountId, String currentStatus) {
        // The stub never actually contacts Stripe, so it has nothing new to report — it reflects
        // whatever status is already stored. Onboarding only ever "completes" via
        // PaymentService.completeStripeOnboarding, per this task's spec.
        return currentStatus;
    }

    @Override
    public String createDestinationCharge(String stripeAccountId, int amountCents, UUID paymentId) {
        String paymentIntentId = "pi_stub_" + randomSuffix();
        log.info("[StubStripeGateway] Would charge {} cents as a destination charge to {} for payment {}: {}",
                amountCents, stripeAccountId, paymentId, paymentIntentId);
        return paymentIntentId;
    }

    @Override
    public String refund(String stripePaymentIntentId) {
        String refundId = "re_stub_" + randomSuffix();
        log.info("[StubStripeGateway] Would refund PaymentIntent {}: {}", stripePaymentIntentId, refundId);
        return refundId;
    }

    private static String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
