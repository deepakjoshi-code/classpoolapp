package app.classpool.api.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invite")
public class Invite {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "classroom_id", nullable = false)
    private UUID classroomId;

    @Column(nullable = false, unique = true)
    private String token;

    @Enumerated(EnumType.STRING)
    private InviteChannel channel;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "converted_at")
    private Instant convertedAt;

    @Column(name = "converted_user_id")
    private UUID convertedUserId;

    protected Invite() {
    }

    public Invite(UUID classroomId, String token, InviteChannel channel, UUID createdBy) {
        this.classroomId = classroomId;
        this.token = token;
        this.channel = channel;
        this.createdBy = createdBy;
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

    public String getToken() {
        return token;
    }

    public InviteChannel getChannel() {
        return channel;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(Instant openedAt) {
        this.openedAt = openedAt;
    }

    public Instant getConvertedAt() {
        return convertedAt;
    }

    public void setConvertedAt(Instant convertedAt) {
        this.convertedAt = convertedAt;
    }

    public UUID getConvertedUserId() {
        return convertedUserId;
    }

    public void setConvertedUserId(UUID convertedUserId) {
        this.convertedUserId = convertedUserId;
    }
}
