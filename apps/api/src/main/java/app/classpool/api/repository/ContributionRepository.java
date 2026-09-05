package app.classpool.api.repository;

import app.classpool.api.domain.Contribution;
import app.classpool.api.domain.ContributionState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContributionRepository extends JpaRepository<Contribution, UUID> {

    /** Every contribution against any of a pool's requirements — the organizer listing endpoint
     *  (PRD §12.3 "View unreceived contributions"). Batched over a pool's requirement ids, same
     *  pattern as {@code ParentInventoryRepository.findByRequirementIdInAndStudentIdIn}. */
    List<Contribution> findByRequirementIdInOrderByCreatedAtAsc(Collection<UUID> requirementIds);

    /** The caller's own contributions across a pool (GET .../contributions/mine) — scoped to
     *  {@code offering_parent_id}, the column this table actually attributes a pledge to. */
    List<Contribution> findByOfferingParentIdAndRequirementIdInOrderByCreatedAtAsc(UUID offeringParentId,
                                                                                    Collection<UUID> requirementIds);

    /** Scoped fetch-by-id: only returns a hit if the contribution belongs to one of this pool's
     *  requirements — the same "never let an id from another tenant resolve" guard as
     *  {@code RequirementRepository.findByIdAndPoolId}, one join further out (contribution ->
     *  requirement -> pool). */
    Optional<Contribution> findByIdAndRequirementIdIn(UUID id, Collection<UUID> requirementIds);

    /**
     * Per-requirement total in a single state, batched across a pool's requirements — used by
     * {@code AllocationService.reconcile} with {@code state = RECEIVED} to compute each
     * requirement's pool-available supply (PRD §5.4/§6.1: a PLEDGED-but-not-yet-received pledge
     * must not count toward what the pool can actually hand out). Same batching instinct as
     * {@code ParentInventoryRepository.sumOwnedQuantityByRequirementIdIn}.
     */
    @Query("select c.requirementId as requirementId, coalesce(sum(c.quantity), 0) as total "
            + "from Contribution c where c.requirementId in :requirementIds and c.state = :state "
            + "group by c.requirementId")
    List<RequirementQuantityTotal> sumQuantityByRequirementIdInAndState(
            @Param("requirementIds") Collection<UUID> requirementIds, @Param("state") ContributionState state);

    interface RequirementQuantityTotal {
        UUID getRequirementId();

        long getTotal();
    }
}
