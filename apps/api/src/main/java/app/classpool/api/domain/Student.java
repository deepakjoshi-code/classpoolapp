package app.classpool.api.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "student")
public class Student {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    // PRD §14: first name/initial only, never a full child profile.
    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Student() {
    }

    public Student(UUID householdId, String firstName) {
        this.householdId = householdId;
        this.firstName = firstName;
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

    public UUID getHouseholdId() {
        return householdId;
    }

    public String getFirstName() {
        return firstName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
