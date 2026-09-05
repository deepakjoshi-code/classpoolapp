package app.classpool.api.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One household's recorded "how many of this requirement do we already own" for a single
 * (requirement, student) pair — PRD §4 "Shop Your Home First". Unique on
 * {@code (requirement_id, student_id)} at the DB level (V1 migration), which is what makes
 * {@code InventoryService.setInventory} an upsert rather than an insert-only operation.
 *
 * {@code surplus_offered_quantity} and {@code condition} from the V1 migration are intentionally
 * unmapped here — both are Phase 5 (surplus/reuse marketplace) concerns; this phase only
 * reads/writes {@code owned_quantity} and leaves the other two columns on their DB defaults
 * ({@code 0} and {@code null} respectively).
 */
@Entity
@Table(name = "parent_inventory")
public class ParentInventory {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "requirement_id", nullable = false)
    private UUID requirementId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "parent_user_id", nullable = false)
    private UUID parentUserId;

    @Column(name = "owned_quantity", nullable = false)
    private int ownedQuantity;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ParentInventory() {
    }

    public ParentInventory(UUID requirementId, UUID studentId, UUID parentUserId, int ownedQuantity) {
        this.requirementId = requirementId;
        this.studentId = studentId;
        this.parentUserId = parentUserId;
        this.ownedQuantity = ownedQuantity;
    }

    @PrePersist
    void prePersist() {
        updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Applies a re-recorded owned quantity (the PUT endpoint's upsert-update path). Also refreshes
     * {@code parentUserId} to the caller doing the writing — this column isn't part of the unique
     * key, so it simply tracks whoever most recently touched the row, which matters once Phase 5
     * lets more than one household member record against the same student.
     */
    public void applyOwnedQuantity(int ownedQuantity, UUID parentUserId) {
        this.ownedQuantity = ownedQuantity;
        this.parentUserId = parentUserId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRequirementId() {
        return requirementId;
    }

    public UUID getStudentId() {
        return studentId;
    }

    public UUID getParentUserId() {
        return parentUserId;
    }

    public int getOwnedQuantity() {
        return ownedQuantity;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
