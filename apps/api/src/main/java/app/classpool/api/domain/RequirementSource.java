package app.classpool.api.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * An import attempt against a {@link Pool} (PRD §3.1/§3.2) — one row per paste/upload, an audit
 * trail of what was submitted and by whom, independent of how many (if any) {@link Requirement}
 * rows it produced. Maps directly onto the V1 migration's already-present {@code
 * requirement_source} table — no new migration needed for Phase 11.
 *
 * <p>{@code s3_key} is intentionally unmapped-to-a-setter here (always {@code null} in V1): every
 * source Phase 11 creates is a pasted-text one ({@link RequirementSourceType#PASTED_EMAIL}/{@link
 * RequirementSourceType#PASTED_PORTAL}/{@link RequirementSourceType#PASTED_MESSAGE}), never a file
 * upload — the column exists for a later phase's PDF/photo/screenshot/Word-doc path (no S3
 * credentials in this sandbox, see README's "AI ingestion" notes).
 */
@Entity
@Table(name = "requirement_source")
public class RequirementSource {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "pool_id", nullable = false)
    private UUID poolId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private RequirementSourceType sourceType;

    @Column(name = "s3_key")
    private String s3Key;

    @Column(name = "raw_text")
    private String rawText;

    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RequirementSource() {
    }

    /** Phase 11's only construction path — {@code s3Key} always {@code null} (see class Javadoc). */
    public RequirementSource(UUID poolId, RequirementSourceType sourceType, String rawText, UUID uploadedBy) {
        this.poolId = poolId;
        this.sourceType = sourceType;
        this.rawText = rawText;
        this.uploadedBy = uploadedBy;
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

    public UUID getPoolId() {
        return poolId;
    }

    public RequirementSourceType getSourceType() {
        return sourceType;
    }

    public String getS3Key() {
        return s3Key;
    }

    public String getRawText() {
        return rawText;
    }

    public UUID getUploadedBy() {
        return uploadedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
