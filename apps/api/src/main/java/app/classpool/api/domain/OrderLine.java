package app.classpool.api.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One {@link PurchasePlanLine}'s actual-purchase outcome (PRD §9.1), created once by {@code
 * OrderService.recordOrder} — one row per {@code PurchasePlanLine} on the pool's approved plan.
 * Maps directly onto the V1 migration's already-present {@code order_line} table.
 *
 * <p><b>Flagged schema/contract gap.</b> The contract's {@code OrderLine} schema also has {@code
 * substitutionDeltaCents}/{@code substitutionResolution}, but the migration's {@code order_line}
 * table has neither column — see {@link SubstitutionResolution}'s Javadoc for why this is the same
 * kind of gap as {@code Payment.method}, and why {@code OrderService} computes both live from
 * {@code actualCostCents} and the referenced {@link PurchasePlanLine#getTotalCostCents()} instead
 * of persisting them.
 */
@Entity
@Table(name = "order_line")
public class OrderLine {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "purchase_plan_line_id", nullable = false)
    private UUID purchasePlanLineId;

    @Column(name = "actual_description")
    private String actualDescription;

    @Column(name = "actual_cost_cents")
    private Integer actualCostCents;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OrderLine() {
    }

    public OrderLine(UUID orderId, UUID purchasePlanLineId, String actualDescription, Integer actualCostCents) {
        this.orderId = orderId;
        this.purchasePlanLineId = purchasePlanLineId;
        this.actualDescription = actualDescription;
        this.actualCostCents = actualCostCents;
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

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getPurchasePlanLineId() {
        return purchasePlanLineId;
    }

    public String getActualDescription() {
        return actualDescription;
    }

    public Integer getActualCostCents() {
        return actualCostCents;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
