package app.classpool.api.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A single supply-list line item within a {@link Pool} (PRD §3). {@code requirement_source_id}
 * from the V1 migration is intentionally unmapped here — Phase 3 is the manual-entry path only
 * (PRD §3.2 update: manual entry is a permanent parallel path, not a pre-AI placeholder), so every
 * Requirement this phase creates has no {@code RequirementSource} row to reference; it stays null
 * via the column's own nullability. {@code sourceEvidence}/{@code confidence} are likewise always
 * null for manual entries — only Phase 11's AI extraction populates them (contract's own
 * description on both fields).
 */
@Entity
@Table(name = "requirement")
public class Requirement {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "pool_id", nullable = false)
    private UUID poolId;

    @Column(nullable = false)
    private String name;

    @Column(name = "quantity_per_student", nullable = false)
    private int quantityPerStudent;

    private String brand;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequirementStrictness strictness;

    @Column(name = "source_evidence")
    private String sourceEvidence;

    private BigDecimal confidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequirementState state;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Requirement() {
    }

    public Requirement(UUID poolId, String name, int quantityPerStudent, String brand,
                        RequirementStrictness strictness) {
        this.poolId = poolId;
        this.name = name;
        this.quantityPerStudent = quantityPerStudent;
        this.brand = brand;
        this.strictness = strictness == null ? RequirementStrictness.EQUIVALENT_ALLOWED : strictness;
        this.state = RequirementState.EXTRACTED;
        // sourceEvidence/confidence stay null: see class Javadoc.
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

    /** The "Correct" action (PRD §3.3) — edits every organizer-settable field at once. */
    public void applyEdit(String name, int quantityPerStudent, String brand, RequirementStrictness strictness) {
        this.name = name;
        this.quantityPerStudent = quantityPerStudent;
        this.brand = brand;
        this.strictness = strictness == null ? RequirementStrictness.EQUIVALENT_ALLOWED : strictness;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPoolId() {
        return poolId;
    }

    public String getName() {
        return name;
    }

    public int getQuantityPerStudent() {
        return quantityPerStudent;
    }

    public String getBrand() {
        return brand;
    }

    public RequirementStrictness getStrictness() {
        return strictness;
    }

    public String getSourceEvidence() {
        return sourceEvidence;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public RequirementState getState() {
        return state;
    }

    public void setState(RequirementState state) {
        this.state = state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
