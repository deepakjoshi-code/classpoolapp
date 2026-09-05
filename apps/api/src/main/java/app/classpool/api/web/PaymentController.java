package app.classpool.api.web;

import app.classpool.api.dto.FinalizePaymentsRequest;
import app.classpool.api.dto.PayPaymentRequest;
import app.classpool.api.dto.PaymentResponse;
import app.classpool.api.dto.PaymentsSummaryResponse;
import app.classpool.api.dto.PoolDetailResponse;
import app.classpool.api.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Phase 9's household-billing surface (PRD §8.1-8.4) — split out from {@link PoolController} for
 * the same size-driven reason {@link PurchasePlanController} was: nine routes here alone, and
 * {@code PoolController} was already at 147 lines.
 */
@RestController
@RequestMapping("/api/v1/pools")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/{poolId}/payments/generate")
    public List<PaymentResponse> generatePayments(@AuthenticationPrincipal UUID callerUserId,
                                                    @PathVariable UUID poolId) {
        return paymentService.generatePayments(callerUserId, poolId);
    }

    @GetMapping("/{poolId}/payments")
    public List<PaymentResponse> listPaymentsForOrganizer(@AuthenticationPrincipal UUID callerUserId,
                                                             @PathVariable UUID poolId) {
        return paymentService.listPaymentsForOrganizer(callerUserId, poolId);
    }

    @GetMapping("/{poolId}/payments/mine")
    public PaymentResponse getMyPayment(@AuthenticationPrincipal UUID callerUserId, @PathVariable UUID poolId) {
        return paymentService.getMyPayment(callerUserId, poolId);
    }

    @PostMapping("/{poolId}/payments/{paymentId}/pay")
    public PaymentResponse payMyPayment(@AuthenticationPrincipal UUID callerUserId, @PathVariable UUID poolId,
                                         @PathVariable UUID paymentId, @Valid @RequestBody PayPaymentRequest request) {
        return paymentService.payMyPayment(callerUserId, poolId, paymentId, request);
    }

    @PostMapping("/{poolId}/payments/{paymentId}/mark-cash-pending")
    public PaymentResponse markPaymentCashPending(@AuthenticationPrincipal UUID callerUserId,
                                                    @PathVariable UUID poolId, @PathVariable UUID paymentId) {
        return paymentService.markPaymentCashPending(callerUserId, poolId, paymentId);
    }

    @PostMapping("/{poolId}/payments/{paymentId}/mark-cash-received")
    public PaymentResponse markPaymentCashReceived(@AuthenticationPrincipal UUID callerUserId,
                                                     @PathVariable UUID poolId, @PathVariable UUID paymentId) {
        return paymentService.markPaymentCashReceived(callerUserId, poolId, paymentId);
    }

    @PostMapping("/{poolId}/payments/{paymentId}/refund")
    public PaymentResponse refundPayment(@AuthenticationPrincipal UUID callerUserId, @PathVariable UUID poolId,
                                          @PathVariable UUID paymentId) {
        return paymentService.refundPayment(callerUserId, poolId, paymentId);
    }

    @GetMapping("/{poolId}/payments/summary")
    public PaymentsSummaryResponse getPaymentsSummary(@AuthenticationPrincipal UUID callerUserId,
                                                        @PathVariable UUID poolId) {
        return paymentService.getPaymentsSummary(callerUserId, poolId);
    }

    @PostMapping("/{poolId}/payments/finalize")
    public PoolDetailResponse finalizePayments(@AuthenticationPrincipal UUID callerUserId,
                                                @PathVariable UUID poolId,
                                                @RequestBody(required = false) FinalizePaymentsRequest request) {
        return paymentService.finalizePayments(callerUserId, poolId, request);
    }
}
