package app.classpool.api.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Leftover pack quantity banked for a classroom's future use (PRD §9.4/§13.1/§19) — one row per
 * {@link PurchasePlanLine} with {@code wasteQuantity > 0}, created once by {@code
 * DistributionService.generateDistribution}. Maps directly onto the V1 migration's already-present
 * {@code class_reserve} table, which is scoped to <em>either</em> a classroom or a school (a check
 * constraint enforces exactly one of the two is set) — this phase only ever creates
 * classroom-scoped rows (per this task's scope), so {@code school_id} is intentionally left
 * unmapped here and always takes its DB default of {@code null}, same "don't map what this phase
 * doesn't populate" instinct as {@code Requirement.requirement_source_id}.
 *
 * <p>{@code custodianLocation} stays {@code null} for every row this phase creates — V1 has no
 * UI/API surface to set it (there's no request field for it anywhere in the contract's {@code
 * generateDistribution} or any other Phase 10 endpoint). Documented as a deliberate V1 gap in
 * apps/api/README.md, not a bug.
 */
@Entity
@Table(name = "class_reserve")
public class ClassReserveEntry {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "classroom_id")
    private UUID classroomId;

    @Column(name = "item_name", nullable = false)
    private String itemName;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "custodian_location")
    private String custodianLocation;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ClassReserveEntry() {
    }

    public ClassReserveEntry(UUID classroomId, String itemName, int quantity) {
        this.classroomId = classroomId;
        this.itemName = itemName;
        this.quantity = quantity;
        // custodianLocation stays null — see class Javadoc.
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

    public UUID getClassroomId() {
        return classroomId;
    }

    public String getItemName() {
        return itemName;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getCustodianLocation() {
        return custodianLocation;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
