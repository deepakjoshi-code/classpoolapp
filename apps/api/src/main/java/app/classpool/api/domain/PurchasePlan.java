package app.classpool.api.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * The frozen output of {@code PurchasePlanService.generate} (PRD §7.1's bulk-pack optimizer),
 * one row per pool (V1-migration's {@code purchase_plan} table has no unique constraint on
 * {@code pool_id}, but {@code PurchasePlanService} enforces "at most one plan per pool" itself —
 * generating a second time 409s, same one-shot instinct as {@code AllocationService.reconcile} and
 * {@code PoolService.confirm}). {@link #lines} isn't mapped as a JPA relationship here — {@code
 * PurchasePlanLineRepository} owns the query, same "service assembles the response, entity doesn't
 * own the collection" pattern as {@code AllocationLine}/{@code ResidualDemandLine} being separate
 * top-level entities rather than a {@code @OneToMany} off {@code Requirement}.
 */
@Entity
@Table(name = "purchase_plan")
public class PurchasePlan {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "pool_id", nullable = false)
    private UUID poolId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PurchasePlanState state;

    @Column(name = "proposed_at", nullable = false)
    private Instant proposedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    protected PurchasePlan() {
    }

    public PurchasePlan(UUID poolId) {
        this.poolId = poolId;
        this.state = PurchasePlanState.PROPOSED;
    }

    @PrePersist
    void prePersist() {
        if (proposedAt == null) {
            proposedAt = Instant.now();
        }
    }

    /** The organizer's approval action (PRD's "organizer selects plan" V1 flow step),
     *  PROPOSED -&gt; APPROVED. Callers must check {@link #getState()} themselves first — this only
     *  applies the transition, matching {@code Contribution.markReceived}'s same division of
     *  labor between entity and service. */
    public void approve() {
        this.state = PurchasePlanState.APPROVED;
        this.approvedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getPoolId() {
        return poolId;
    }

    public PurchasePlanState getState() {
        return state;
    }

    public Instant getProposedAt() {
        return proposedAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }
}
