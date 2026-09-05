package app.classpool.api.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A household's pledge of surplus against a {@link Requirement} (PRD §5 "Surplus Contributions and
 * Exchange Pool"). Attributed to the offering <em>parent</em>, not a specific student —
 * {@code offering_parent_id} is the V1 migration's column, and there is no {@code student_id} on
 * this table (see {@code ContributionService.offer}'s Javadoc: the caller's per-student Membership
 * is checked at creation time as an authorization gate only, not persisted here). {@code
 * return_due_date} from the V1 migration is intentionally unmapped — it's a Lend-mode (PRD §5.4
 * PM-update) concern, and V1 only ever creates {@link ContributionMode#DONATE} rows.
 */
@Entity
@Table(name = "contribution")
public class Contribution {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "requirement_id", nullable = false)
    private UUID requirementId;

    @Column(name = "offering_parent_id", nullable = false)
    private UUID offeringParentId;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContributionMode mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContributionState state;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Contribution() {
    }

    public Contribution(UUID requirementId, UUID offeringParentId, int quantity, ContributionMode mode) {
        this.requirementId = requirementId;
        this.offeringParentId = offeringParentId;
        this.quantity = quantity;
        this.mode = mode;
        this.state = ContributionState.PLEDGED;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    /** The organizer's confirmation action (PRD §5.4: {@code PLEDGED -> RECEIVED}). Callers must
     *  check {@link #getState()} themselves first — this only applies the transition, it doesn't
     *  guard against re-applying it (that 409 lives in {@code ContributionService}, matching how
     *  {@code RequirementService}/{@code PoolService} keep state-machine guards at the service
     *  layer rather than on the entity). */
    public void markReceived() {
        this.state = ContributionState.RECEIVED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRequirementId() {
        return requirementId;
    }

    public UUID getOfferingParentId() {
        return offeringParentId;
    }

    public int getQuantity() {
        return quantity;
    }

    public ContributionMode getMode() {
        return mode;
    }

    public ContributionState getState() {
        return state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
