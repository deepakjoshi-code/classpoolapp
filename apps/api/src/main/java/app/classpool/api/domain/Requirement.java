package app.classpool.api.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A single supply-list line item within a {@link Pool} (PRD §3). {@code requirement_source_id}/
 * {@code source_evidence}/{@code confidence} stay {@code null} for every Requirement created via
 * the manual-entry constructor below ({@code RequirementService.add}, PRD §3.2 update: manual
 * entry is a permanent parallel path, not a pre-AI placeholder) — {@link #attachExtractionSource}
 * is the only way those three fields ever get populated, and only Phase 11's {@code
 * RequirementImportService} calls it, for a Requirement produced by {@code
 * AiExtractionGateway.extract}.
 */
@Entity
@Table(name = "requirement")
public class Requirement {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "pool_id", nullable = false)
    private UUID poolId;

    @Column(name = "requirement_source_id")
    private UUID requirementSourceId;

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

    /**
     * Phase 11's only mutation path for these three fields — called once, right after
     * construction, by {@code RequirementImportService} for a Requirement built from an {@code
     * AiExtractionGateway.ExtractedRequirement}. {@code state} is passed in rather than derived
     * here so the 0.85 confidence-threshold policy stays in the service layer (matching every other
     * state-machine decision in this codebase — see {@code RequirementService}/{@code PoolService}).
     */
    public void attachExtractionSource(UUID requirementSourceId, String sourceEvidence, BigDecimal confidence,
                                        RequirementState state) {
        this.requirementSourceId = requirementSourceId;
        this.sourceEvidence = sourceEvidence;
        this.confidence = confidence;
        this.state = state;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPoolId() {
        return poolId;
    }

    public UUID getRequirementSourceId() {
        return requirementSourceId;
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
