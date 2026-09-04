package app.classpool.api.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A pool run against a classroom (PRD §2.3 — "a class can run multiple pools per year"). Only the
 * columns Phase 3 reads/writes are mapped here; {@code payment_gate_satisfied},
 * {@code payment_threshold_pct} and {@code locked_at} exist in the V1 migration for later phases
 * (Payment Unlock Gate, purchase-lock) and take their DB defaults on insert since this entity
 * never sets them.
 */
@Entity
@Table(name = "pool")
public class Pool {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "classroom_id", nullable = false)
    private UUID classroomId;

    @Column(nullable = false)
    private String name;

    @Column(name = "pool_type", nullable = false)
    private String poolType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PoolState state;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Pool() {
    }

    public Pool(UUID classroomId, String name, String poolType) {
        this.classroomId = classroomId;
        this.name = name;
        this.poolType = poolType;
        this.state = PoolState.DRAFT;
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

    public String getName() {
        return name;
    }

    public String getPoolType() {
        return poolType;
    }

    public PoolState getState() {
        return state;
    }

    public void setState(PoolState state) {
        this.state = state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
