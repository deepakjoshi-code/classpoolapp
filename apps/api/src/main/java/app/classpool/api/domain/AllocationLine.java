package app.classpool.api.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Frozen per-(requirement, student) outcome of the allocation engine (PRD §6), written once by
 * {@code AllocationService.reconcile} and read back verbatim afterward by {@code GET
 * /pools/{poolId}/allocation} and {@code /allocation/mine} — never recomputed live, the same
 * "freeze a snapshot, don't recompute" instinct as {@link Pool#getConfirmedStudentCount()} /
 * {@code Requirement.totalDemand} (see apps/api/README.md's Phase 3 and Phase 6/7 notes). Re-
 * running reconcile is not supported in V1, so this table is write-once per
 * {@code (requirement_id, student_id)} — enforced by the V3 migration's unique constraint.
 */
@Entity
@Table(name = "allocation_line")
public class AllocationLine {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "requirement_id", nullable = false)
    private UUID requirementId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "quantity_needed", nullable = false)
    private int quantityNeeded;

    @Column(name = "owned_quantity", nullable = false)
    private int ownedQuantity;

    @Column(name = "pool_fulfilled_quantity", nullable = false)
    private int poolFulfilledQuantity;

    @Column(name = "purchase_required_quantity", nullable = false)
    private int purchaseRequiredQuantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AllocationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AllocationLine() {
    }

    public AllocationLine(UUID requirementId, UUID studentId, int quantityNeeded, int ownedQuantity,
                           int poolFulfilledQuantity, int purchaseRequiredQuantity, AllocationStatus status) {
        this.requirementId = requirementId;
        this.studentId = studentId;
        this.quantityNeeded = quantityNeeded;
        this.ownedQuantity = ownedQuantity;
        this.poolFulfilledQuantity = poolFulfilledQuantity;
        this.purchaseRequiredQuantity = purchaseRequiredQuantity;
        this.status = status;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
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

    public int getQuantityNeeded() {
        return quantityNeeded;
    }

    public int getOwnedQuantity() {
        return ownedQuantity;
    }

    public int getPoolFulfilledQuantity() {
        return poolFulfilledQuantity;
    }

    public int getPurchaseRequiredQuantity() {
        return purchaseRequiredQuantity;
    }

    public AllocationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
