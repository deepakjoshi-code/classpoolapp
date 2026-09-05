package app.classpool.api.repository;

import app.classpool.api.domain.ResidualDemandLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ResidualDemandLineRepository extends JpaRepository<ResidualDemandLine, UUID> {

    /** One row per requirement (V3 migration's unique constraint) — the class-level aggregate half
     *  of GET /pools/{poolId}/allocation's AllocationSummary. */
    List<ResidualDemandLine> findByRequirementIdInOrderByRequirementIdAsc(Collection<UUID> requirementIds);
}
