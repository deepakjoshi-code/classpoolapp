package app.classpool.api.repository;

import app.classpool.api.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    /** At most one order per pool in V1 (enforced in the service, not a DB constraint — same
     *  boundary as {@code PurchasePlanRepository.findByPoolId}). */
    Optional<Order> findByPoolId(UUID poolId);

    boolean existsByPoolId(UUID poolId);
}
