package app.classpool.api.repository;

import app.classpool.api.domain.PurchasePlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PurchasePlanRepository extends JpaRepository<PurchasePlan, UUID> {

    /** At most one plan per pool in V1 (enforced in the service, not a DB constraint — see
     *  {@code PurchasePlan}'s Javadoc). */
    Optional<PurchasePlan> findByPoolId(UUID poolId);

    boolean existsByPoolId(UUID poolId);
}
