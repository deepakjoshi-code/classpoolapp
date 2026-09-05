package app.classpool.api.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * The organizer's record that a pool's approved {@link PurchasePlan} was actually bought (PRD
 * §9.1), created once by {@code OrderService.recordOrder}. Maps directly onto the V1 migration's
 * already-present {@code "order"} table — quoted in the migration (and here, via backtick
 * Hibernate-quoting so the dialect's own quote character is used) because {@code order} is a SQL
 * reserved word. One-shot per pool, enforced in the service (no unique constraint on {@code
 * pool_id} in the migration), same boundary as {@code PurchasePlan}'s "at most one plan per pool"
 * javadoc.
 */
@Entity
@Table(name = "`order`")
public class Order {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "pool_id", nullable = false)
    private UUID poolId;

    @Column(name = "ordered_by", nullable = false)
    private UUID orderedBy;

    @Column(name = "receipt_s3_key")
    private String receiptS3Key;

    @Column(name = "ordered_at", nullable = false, updatable = false)
    private Instant orderedAt;

    protected Order() {
    }

    public Order(UUID poolId, UUID orderedBy, String receiptS3Key) {
        this.poolId = poolId;
        this.orderedBy = orderedBy;
        this.receiptS3Key = receiptS3Key;
    }

    @PrePersist
    void prePersist() {
        if (orderedAt == null) {
            orderedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getPoolId() {
        return poolId;
    }

    public UUID getOrderedBy() {
        return orderedBy;
    }

    public String getReceiptS3Key() {
        return receiptS3Key;
    }

    public Instant getOrderedAt() {
        return orderedAt;
    }
}
