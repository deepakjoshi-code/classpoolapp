package app.classpool.api.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A candidate retailer pack offer against one {@link Requirement} (PRD §7.3), the raw material
 * {@code PurchasePlanService}'s bulk-pack optimizer (PRD §7.1) chooses among. Maps directly onto
 * the V1 migration's already-present {@code product_offer} table — no schema changes needed for
 * this phase (unlike Phase 6/7's {@code allocation} table, this one already has exactly the
 * columns the contract needs).
 *
 * <p>{@code minimumOrderCents} and {@code reliabilityScore} are V1-migration columns not yet
 * surfaced by the contract's {@code ProductOffer} schema (no such fields there) — mapped here for
 * completeness/future use, but nothing in this phase reads or writes them (V1-scoped optimizer
 * only compares {@code priceCents}, see {@code PackOptimizer}'s Javadoc for why shipping/tax
 * modeling is explicitly out of scope too).
 */
@Entity
@Table(name = "product_offer")
public class ProductOffer {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "requirement_id", nullable = false)
    private UUID requirementId;

    @Column(nullable = false)
    private String retailer;

    @Column(name = "pack_quantity", nullable = false)
    private int packQuantity;

    @Column(name = "price_cents", nullable = false)
    private int priceCents;

    @Column(name = "shipping_cents", nullable = false)
    private int shippingCents;

    @Column(name = "affiliate_url")
    private String affiliateUrl;

    @Column(name = "delivery_date")
    private LocalDate deliveryDate;

    @Column(name = "minimum_order_cents")
    private Integer minimumOrderCents;

    @Column(name = "reliability_score")
    private BigDecimal reliabilityScore;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProductOffer() {
    }

    public ProductOffer(UUID requirementId, String retailer, int packQuantity, int priceCents, int shippingCents,
                         String affiliateUrl) {
        this.requirementId = requirementId;
        this.retailer = retailer;
        this.packQuantity = packQuantity;
        this.priceCents = priceCents;
        this.shippingCents = shippingCents;
        this.affiliateUrl = affiliateUrl;
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

    public String getRetailer() {
        return retailer;
    }

    public int getPackQuantity() {
        return packQuantity;
    }

    public int getPriceCents() {
        return priceCents;
    }

    public int getShippingCents() {
        return shippingCents;
    }

    public String getAffiliateUrl() {
        return affiliateUrl;
    }

    public LocalDate getDeliveryDate() {
        return deliveryDate;
    }

    public Integer getMinimumOrderCents() {
        return minimumOrderCents;
    }

    public BigDecimal getReliabilityScore() {
        return reliabilityScore;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
