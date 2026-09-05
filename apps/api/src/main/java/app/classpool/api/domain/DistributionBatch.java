package app.classpool.api.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * The organizer's one-shot "generate distribution" action (PRD §9.2/§9.3), created once by {@code
 * DistributionService.generateDistribution}. Maps directly onto the V1 migration's already-present
 * {@code distribution_batch} table — one-shot per pool enforced in the service (no unique
 * constraint on {@code pool_id}), same boundary as {@code PurchasePlan}/{@code Order}.
 */
@Entity
@Table(name = "distribution_batch")
public class DistributionBatch {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "pool_id", nullable = false)
    private UUID poolId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DistributionMode mode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DistributionBatch() {
    }

    public DistributionBatch(UUID poolId, DistributionMode mode) {
        this.poolId = poolId;
        this.mode = mode;
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

    public UUID getPoolId() {
        return poolId;
    }

    public DistributionMode getMode() {
        return mode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
