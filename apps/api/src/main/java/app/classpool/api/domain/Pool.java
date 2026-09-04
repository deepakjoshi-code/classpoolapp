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

    /**
     * Snapshotted once, at {@code confirm()} time — the number of distinct students already
     * joined to this pool's classroom (PRD §3.4's "confirmed number of participating students").
     * Null while the pool is still DRAFT. Frozen rather than recomputed live so a family joining
     * later (e.g. a late joiner, PRD §13.3) never silently changes an already-confirmed
     * requirement's total demand — see migration V2's comment for the bug this fixes.
     */
    @Column(name = "confirmed_student_count")
    private Integer confirmedStudentCount;

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

    public Integer getConfirmedStudentCount() {
        return confirmedStudentCount;
    }

    public void setConfirmedStudentCount(Integer confirmedStudentCount) {
        this.confirmedStudentCount = confirmedStudentCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
