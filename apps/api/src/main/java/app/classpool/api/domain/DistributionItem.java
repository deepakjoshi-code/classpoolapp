package app.classpool.api.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One (requirement, student) physical hand-over line within a {@link DistributionBatch} (PRD
 * §9.2/§9.3), created once by {@code DistributionService.generateDistribution} from the Phase 6/7
 * {@code AllocationLine} snapshot — {@code quantity = poolFulfilledQuantity +
 * purchaseRequiredQuantity}; a line where both are zero (fully self-fulfilled from household
 * inventory) is skipped entirely, since there is nothing to hand over. Maps directly onto the V1
 * migration's already-present {@code distribution_item} table, which has no {@code created_at}
 * column (unlike most other entities in this codebase) — only {@code delivered_at}, mutated once by
 * {@link #markDelivered()}.
 */
@Entity
@Table(name = "distribution_item")
public class DistributionItem {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "distribution_batch_id", nullable = false)
    private UUID distributionBatchId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "requirement_id", nullable = false)
    private UUID requirementId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected DistributionItem() {
    }

    public DistributionItem(UUID distributionBatchId, UUID studentId, UUID requirementId, int quantity) {
        this.distributionBatchId = distributionBatchId;
        this.studentId = studentId;
        this.requirementId = requirementId;
        this.quantity = quantity;
    }

    /** {@code markDistributionItemDelivered} (contract). Callers must check {@link
     *  #getDeliveredAt()} is still null first — 409 otherwise, same division of labor as {@code
     *  Contribution.markReceived}. */
    public void markDelivered() {
        this.deliveredAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getDistributionBatchId() {
        return distributionBatchId;
    }

    public UUID getStudentId() {
        return studentId;
    }

    public UUID getRequirementId() {
        return requirementId;
    }

    public int getQuantity() {
        return quantity;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }
}
