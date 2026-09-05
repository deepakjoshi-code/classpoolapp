package app.classpool.api.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Frozen per-requirement aggregate of the allocation engine (PRD §6's ResidualDemand formula),
 * one row per requirement (V3 migration's unique constraint). {@code residualDemand} is what
 * later phases (the bulk optimizer, PRD §7) size a purchase against — {@code
 * totalRequired - totalOwned - totalPoolFulfilled}, clamped at {@code >= 0}. ClassReserveAvailable
 * is fixed at 0 in this phase (Class Reserve doesn't exist until Phase 10), so it isn't a column
 * here at all rather than a column that's always zero.
 */
@Entity
@Table(name = "residual_demand_line")
public class ResidualDemandLine {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "requirement_id", nullable = false)
    private UUID requirementId;

    @Column(name = "total_required", nullable = false)
    private int totalRequired;

    @Column(name = "total_owned", nullable = false)
    private int totalOwned;

    @Column(name = "total_pool_fulfilled", nullable = false)
    private int totalPoolFulfilled;

    @Column(name = "residual_demand", nullable = false)
    private int residualDemand;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ResidualDemandLine() {
    }

    public ResidualDemandLine(UUID requirementId, int totalRequired, int totalOwned, int totalPoolFulfilled,
                               int residualDemand) {
        this.requirementId = requirementId;
        this.totalRequired = totalRequired;
        this.totalOwned = totalOwned;
        this.totalPoolFulfilled = totalPoolFulfilled;
        this.residualDemand = residualDemand;
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

    public int getTotalRequired() {
        return totalRequired;
    }

    public int getTotalOwned() {
        return totalOwned;
    }

    public int getTotalPoolFulfilled() {
        return totalPoolFulfilled;
    }

    public int getResidualDemand() {
        return residualDemand;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
