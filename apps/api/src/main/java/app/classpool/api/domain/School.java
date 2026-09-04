package app.classpool.api.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "school")
public class School {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String name;

    // PRD §13.1 update: backs the Payment Unlock Gate's school-domain-match condition (future phase).
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "approved_email_domains", columnDefinition = "text[]", nullable = false)
    private String[] approvedEmailDomains = new String[0];

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected School() {
    }

    public School(String name) {
        this.name = name;
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

    public String getName() {
        return name;
    }

    public String[] getApprovedEmailDomains() {
        return approvedEmailDomains;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
