package app.classpool.api.repository;

import app.classpool.api.domain.OrganizerStripeAccount;
import app.classpool.api.domain.OrganizerStripeAccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizerStripeAccountRepository extends JpaRepository<OrganizerStripeAccount, UUID> {

    /** The V1 migration's own unique key — onboarding is tracked per organizer/classroom pair
     *  (see {@link OrganizerStripeAccount}'s Javadoc for why). */
    Optional<OrganizerStripeAccount> findByUserIdAndClassroomId(UUID userId, UUID classroomId);

    /** {@code PaymentService.generatePayments}'s "is this classroom ready to take payments" gate —
     *  any organizer's {@code ACTIVE} account on the classroom is usable as the payout
     *  destination, not necessarily the caller's own. */
    boolean existsByClassroomIdAndStatus(UUID classroomId, OrganizerStripeAccountStatus status);

    /** The account {@code payMyPayment}/{@code refundPayment} actually charge/refund against.
     *  Ordered so a classroom with (hypothetically) more than one {@code ACTIVE} account still
     *  resolves deterministically. */
    List<OrganizerStripeAccount> findByClassroomIdAndStatusOrderByCreatedAtAsc(UUID classroomId,
                                                                                OrganizerStripeAccountStatus status);
}
