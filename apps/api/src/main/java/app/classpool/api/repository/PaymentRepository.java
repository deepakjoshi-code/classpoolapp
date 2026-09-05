package app.classpool.api.repository;

import app.classpool.api.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** One-shot guard for {@code generatePayments} — mirrors {@code
     *  PurchasePlanRepository.existsByPoolId}'s "at most one generation per pool" instinct. */
    boolean existsByPoolId(UUID poolId);

    /** {@code listPaymentsForOrganizer}/{@code getPaymentsSummary}'s full read-back. */
    List<Payment> findByPoolIdOrderByCreatedAtAsc(UUID poolId);

    /** {@code getMyPayment} — the caller's own household's single row for this pool, or empty if
     *  payments haven't been generated yet or this household had no residual demand. */
    Optional<Payment> findByPoolIdAndHouseholdId(UUID poolId, UUID householdId);

    /** Scoped fetch-by-id, same "never let an id from another pool resolve" guard as {@code
     *  RequirementRepository.findByIdAndPoolId}. */
    Optional<Payment> findByIdAndPoolId(UUID id, UUID poolId);
}
