package app.classpool.api.repository;

import app.classpool.api.domain.DistributionBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DistributionBatchRepository extends JpaRepository<DistributionBatch, UUID> {

    /** At most one batch per pool in V1 (enforced in the service, not a DB constraint — same
     *  boundary as {@code OrderRepository.findByPoolId}). */
    Optional<DistributionBatch> findByPoolId(UUID poolId);

    boolean existsByPoolId(UUID poolId);
}
