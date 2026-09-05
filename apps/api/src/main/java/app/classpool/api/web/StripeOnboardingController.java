package app.classpool.api.web;

import app.classpool.api.dto.OrganizerStripeAccountResponse;
import app.classpool.api.service.PaymentService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Phase 9's Stripe Express onboarding surface (PRD §8.4) — kept separate from {@link
 * ClassroomController} despite being classroom-scoped, since it and {@link PaymentController}
 * share one {@code PaymentService} and read more naturally grouped with the payments feature they
 * gate than folded into the classroom CRUD file.
 */
@RestController
@RequestMapping("/api/v1/classrooms")
public class StripeOnboardingController {

    private final PaymentService paymentService;

    public StripeOnboardingController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/{classroomId}/stripe-onboarding")
    public OrganizerStripeAccountResponse startStripeOnboarding(@AuthenticationPrincipal UUID callerUserId,
                                                                  @PathVariable UUID classroomId) {
        return paymentService.startStripeOnboarding(callerUserId, classroomId);
    }

    @PostMapping("/{classroomId}/stripe-onboarding/complete")
    public OrganizerStripeAccountResponse completeStripeOnboarding(@AuthenticationPrincipal UUID callerUserId,
                                                                     @PathVariable UUID classroomId) {
        return paymentService.completeStripeOnboarding(callerUserId, classroomId);
    }

    @GetMapping("/{classroomId}/stripe-onboarding/status")
    public OrganizerStripeAccountResponse getStripeOnboardingStatus(@AuthenticationPrincipal UUID callerUserId,
                                                                      @PathVariable UUID classroomId) {
        return paymentService.getStripeOnboardingStatus(callerUserId, classroomId);
    }
}
