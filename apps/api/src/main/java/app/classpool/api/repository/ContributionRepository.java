package app.classpool.api.repository;

import app.classpool.api.domain.Contribution;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
