package app.classpool.api.repository;

import app.classpool.api.domain.ParentInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ParentInventoryRepository extends JpaRepository<ParentInventory, UUID> {

    /** The upsert key (PRD §4) — DB-unique on (requirement_id, student_id). */
    Optional<ParentInventory> findByRequirementIdAndStudentId(UUID requirementId, UUID studentId);

    /** Every recorded line for a pool's requirements x a specific set of students (a caller's own
     *  students in the classroom) — batched, so GET /pools/{poolId}/inventory needs one query
     *  regardless of how many (requirement, student) pairs it's assembling. */
    List<ParentInventory> findByRequirementIdInAndStudentIdIn(Collection<UUID> requirementIds,
                                                               Collection<UUID> studentIds);

    /**
     * Distinct students with at least one inventory line recorded against this pool's requirements
     * — the numerator of GET /pools/{poolId}/inventory/summary's {@code studentsWithInventorySubmitted}
     * (PRD §12.3's "Inventory completed 19/25").
     */
    @Query("select count(distinct pi.studentId) from ParentInventory pi where pi.requirementId in :requirementIds")
    long countDistinctStudentsByRequirementIdIn(@Param("requirementIds") Collection<UUID> requirementIds);

    /**
     * Per-requirement {@code totalOwned} (sum of owned_quantity across every household) for the
     * summary endpoint — batched across all of a pool's requirements in one query rather than one
     * per requirement.
     */
    @Query("select pi.requirementId as requirementId, coalesce(sum(pi.ownedQuantity), 0) as total "
            + "from ParentInventory pi where pi.requirementId in :requirementIds group by pi.requirementId")
    List<RequirementOwnedTotal> sumOwnedQuantityByRequirementIdIn(@Param("requirementIds") Collection<UUID> requirementIds);

    interface RequirementOwnedTotal {
        UUID getRequirementId();

        long getTotal();
    }
}
