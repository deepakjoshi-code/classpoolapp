package app.classpool.api.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Models two distinct things on one row, matching PRD §2.1 exactly: a parent's household
 * participation in a classroom (via a specific student), and/or a user's ORGANIZER permission
 * grant on a classroom. An ORGANIZER row's student may be null.
 *
 * Every cross-tenant query filters by classroomId — this is the backbone of the PRD §14
 * "Class A can never read Class B" authorization test.
 */
@Entity
@Table(name = "membership")
public class Membership {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "classroom_id", nullable = false)
    private Classroom classroom;

    @Column(name = "parent_user_id", nullable = false)
    private UUID parentUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MembershipRole role;

    @Column(name = "late_join", nullable = false)
    private boolean lateJoin = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Membership() {
    }

    public Membership(Classroom classroom, UUID parentUserId, Student student, MembershipRole role,
                       boolean lateJoin) {
        this.classroom = classroom;
        this.parentUserId = parentUserId;
        this.student = student;
        this.role = role;
        this.lateJoin = lateJoin;
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

    public Classroom getClassroom() {
        return classroom;
    }

    public UUID getParentUserId() {
        return parentUserId;
    }

    public Student getStudent() {
        return student;
    }

    public MembershipRole getRole() {
        return role;
    }

    public boolean isLateJoin() {
        return lateJoin;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
