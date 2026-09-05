package app.classpool.api.repository;

import app.classpool.api.domain.DistributionItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DistributionItemRepository extends JpaRepository<DistributionItem, UUID> {

    /** The organizer's full snapshot read-back (GET /pools/{poolId}/distribution) — every item in
     *  a batch, same "one query, stable order" instinct as {@code AllocationLineRepository
     *  .findByRequirementIdInOrderByRequirementIdAscStudentIdAsc}. */
    List<DistributionItem> findByDistributionBatchIdOrderByRequirementIdAscStudentIdAsc(UUID distributionBatchId);

    /** A caller's own students' items only (GET /pools/{poolId}/distribution/mine) — the privacy
     *  boundary {@code DistributionService.getMyDistribution} enforces. */
    List<DistributionItem> findByDistributionBatchIdAndStudentIdInOrderByRequirementIdAscStudentIdAsc(
            UUID distributionBatchId, Collection<UUID> studentIds);

    /** Scoped fetch for {@code markDistributionItemDelivered} — never lets one pool's caller
     *  reach into another pool's distribution batch via a guessed item id. */
    Optional<DistributionItem> findByIdAndDistributionBatchId(UUID id, UUID distributionBatchId);
}
