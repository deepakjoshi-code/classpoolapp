package app.classpool.api.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "school_year")
public class SchoolYear {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "school_id", nullable = false)
    private UUID schoolId;

    @Column(nullable = false)
    private String label;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SchoolYear() {
    }

    public SchoolYear(UUID schoolId, String label) {
        this.schoolId = schoolId;
        this.label = label;
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

    public UUID getSchoolId() {
        return schoolId;
    }

    public String getLabel() {
        return label;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
