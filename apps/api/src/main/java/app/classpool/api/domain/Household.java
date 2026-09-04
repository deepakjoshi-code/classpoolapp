package app.classpool.api.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "household")
public class Household {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "primary_parent_id", nullable = false)
    private UUID primaryParentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Household() {
    }

    public Household(UUID primaryParentId) {
        this.primaryParentId = primaryParentId;
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

    public UUID getPrimaryParentId() {
        return primaryParentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
