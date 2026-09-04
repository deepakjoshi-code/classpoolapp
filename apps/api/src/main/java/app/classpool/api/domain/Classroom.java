package app.classpool.api.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "classroom")
public class Classroom {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "school_year_id", nullable = false)
    private UUID schoolYearId;

    @Column(nullable = false)
    private String grade;

    // Teacher has no account — descriptive only (PRD §2.1: teacher optional, never required).
    @Column(name = "teacher_label", nullable = false)
    private String teacherLabel;

    // citext, same reasoning as AppUser.email above.
    @Column(name = "teacher_email", columnDefinition = "citext")
    private String teacherEmail;

    @Column(name = "student_count_estimate")
    private Integer studentCountEstimate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Classroom() {
    }

    public Classroom(UUID schoolYearId, String grade, String teacherLabel, String teacherEmail,
                      Integer studentCountEstimate) {
        this.schoolYearId = schoolYearId;
        this.grade = grade;
        this.teacherLabel = teacherLabel;
        this.teacherEmail = teacherEmail;
        this.studentCountEstimate = studentCountEstimate;
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

    public UUID getSchoolYearId() {
        return schoolYearId;
    }

    public String getGrade() {
        return grade;
    }

    public String getTeacherLabel() {
        return teacherLabel;
    }

    public String getTeacherEmail() {
        return teacherEmail;
    }

    public Integer getStudentCountEstimate() {
        return studentCountEstimate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
