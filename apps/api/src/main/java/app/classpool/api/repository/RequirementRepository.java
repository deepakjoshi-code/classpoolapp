package app.classpool.api.repository;

import app.classpool.api.domain.Requirement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RequirementRepository extends JpaRepository<Requirement, UUID> {

    List<Requirement> findByPoolIdOrderByCreatedAtAsc(UUID poolId);

    Optional<Requirement> findByIdAndPoolId(UUID id, UUID poolId);

    /**
     * Batch requirement counts for PoolAssembler, which attaches {@code requirementCount} to
     * every Pool in a listing without an N+1 count query per pool.
     */
    @Query("select r.poolId as poolId, count(r) as total from Requirement r where r.poolId in :poolIds group by r.poolId")
    List<PoolRequirementCount> countByPoolIdIn(@Param("poolIds") List<UUID> poolIds);

    interface PoolRequirementCount {
        UUID getPoolId();

        long getTotal();
    }
}
