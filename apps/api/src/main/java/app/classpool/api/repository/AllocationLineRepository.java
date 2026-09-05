package app.classpool.api.repository;

import app.classpool.api.domain.AllocationLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AllocationLineRepository extends JpaRepository<AllocationLine, UUID> {

    /** The organizer's full snapshot read-back (GET /pools/{poolId}/allocation) — every line
     *  across a pool's requirements, batched over requirement ids same as
     *  {@code ContributionRepository.findByRequirementIdInOrderByCreatedAtAsc}. */
    List<AllocationLine> findByRequirementIdInOrderByRequirementIdAscStudentIdAsc(Collection<UUID> requirementIds);

    /** A caller's own students' lines only (GET /pools/{poolId}/allocation/mine) — the privacy
     *  boundary {@code AllocationService.getMyAllocation} enforces. */
    List<AllocationLine> findByRequirementIdInAndStudentIdInOrderByRequirementIdAscStudentIdAsc(
            Collection<UUID> requirementIds, Collection<UUID> studentIds);
}
