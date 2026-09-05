package app.classpool.api.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One (requirement, offer) purchase decision within a {@link PurchasePlan} (PRD §7.1/§9.4),
 * written once by {@code PurchasePlanService.generate} and read back verbatim afterward — same
 * "freeze a snapshot, don't recompute" instinct as {@link AllocationLine}/{@link
 * ResidualDemandLine}. A requirement whose optimal combination spans more than one offer gets one
 * row per distinct offer used; {@code wasteQuantity} for that requirement is attributed to exactly
 * one of those rows (see {@code PackOptimizer}'s Javadoc), never split or double-counted across
 * them.
 *
 * <p>{@code substitutionNote}/{@code substitutionDeltaCents}/{@code substitutionDeltaResolution}
 * are V1-migration columns for PRD §9.1's substitution/top-up flow — a later phase's concern (the
 * contract's {@code PurchasePlanLine} schema has no such fields yet), so they're intentionally
 * left unmapped here, same "don't map what this phase doesn't populate" instinct as {@code
 * Contribution}'s unmapped {@code return_due_date}.
 */
@Entity
@Table(name = "purchase_plan_line")
public class PurchasePlanLine {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "purchase_plan_id", nullable = false)
    private UUID purchasePlanId;

    @Column(name = "requirement_id", nullable = false)
    private UUID requirementId;

    @Column(name = "product_offer_id")
    private UUID productOfferId;

    @Column(name = "pack_count", nullable = false)
    private int packCount;

    @Column(name = "total_cost_cents", nullable = false)
    private int totalCostCents;

    @Column(name = "waste_quantity", nullable = false)
    private int wasteQuantity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PurchasePlanLine() {
    }

    public PurchasePlanLine(UUID purchasePlanId, UUID requirementId, UUID productOfferId, int packCount,
                             int totalCostCents, int wasteQuantity) {
        this.purchasePlanId = purchasePlanId;
        this.requirementId = requirementId;
        this.productOfferId = productOfferId;
        this.packCount = packCount;
        this.totalCostCents = totalCostCents;
        this.wasteQuantity = wasteQuantity;
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

    public UUID getPurchasePlanId() {
        return purchasePlanId;
    }

    public UUID getRequirementId() {
        return requirementId;
    }

    public UUID getProductOfferId() {
        return productOfferId;
    }

    public int getPackCount() {
        return packCount;
    }

    public int getTotalCostCents() {
        return totalCostCents;
    }

    public int getWasteQuantity() {
        return wasteQuantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
