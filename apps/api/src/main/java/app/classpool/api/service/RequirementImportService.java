package app.classpool.api.service;

import app.classpool.api.domain.Pool;
import app.classpool.api.domain.PoolState;
import app.classpool.api.domain.Requirement;
import app.classpool.api.domain.RequirementSource;
import app.classpool.api.domain.RequirementSourceType;
import app.classpool.api.domain.RequirementState;
import app.classpool.api.dto.ImportRequirementsRequest;
import app.classpool.api.dto.RequirementImportResultResponse;
import app.classpool.api.dto.RequirementResponse;
import app.classpool.api.dto.RequirementSourceResponse;
import app.classpool.api.exception.BadRequestException;
import app.classpool.api.exception.ConflictException;
import app.classpool.api.repository.RequirementRepository;
import app.classpool.api.repository.RequirementSourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AI-assisted requirement import (PRD §3.1/§3.2, Phase 11) — the pasted-text counterpart to {@code
 * RequirementService}'s manual entry path, sharing the same {@code Pool}/{@code Requirement}
 * table, organizer-only, DRAFT-only gate. See README's "AI ingestion (Phase 11)" notes for the
 * {@link AiExtractionGateway} stub-vs-real boundary and the 0.85 confidence threshold rationale.
 */
@Service
public class RequirementImportService {

    /**
     * PRD §3.2 update / contract: at or above this, a Requirement is created {@code EXTRACTED}
     * (still requires organizer confirmation like every requirement — never auto-CONFIRMED);
     * below it, {@code NEEDS_REVIEW} (the organizer must Correct/Edit before it can be confirmed).
     */
    static final double CONFIDENCE_THRESHOLD = 0.85;

    private static final Set<RequirementSourceType> ALLOWED_IMPORT_SOURCE_TYPES = EnumSet.of(
            RequirementSourceType.PASTED_EMAIL, RequirementSourceType.PASTED_PORTAL,
            RequirementSourceType.PASTED_MESSAGE);

    private final RequirementSourceRepository requirementSourceRepository;
    private final RequirementRepository requirementRepository;
    private final PoolService poolService;
    private final AiExtractionGateway aiExtractionGateway;
    private final RequirementAssembler requirementAssembler;

    public RequirementImportService(RequirementSourceRepository requirementSourceRepository,
                                     RequirementRepository requirementRepository, PoolService poolService,
                                     AiExtractionGateway aiExtractionGateway,
                                     RequirementAssembler requirementAssembler) {
        this.requirementSourceRepository = requirementSourceRepository;
        this.requirementRepository = requirementRepository;
        this.poolService = poolService;
        this.aiExtractionGateway = aiExtractionGateway;
        this.requirementAssembler = requirementAssembler;
    }

    /**
     * Records the paste as a {@link RequirementSource} unconditionally, then creates one {@link
     * Requirement} per {@link AiExtractionGateway.ExtractedRequirement} the gateway found — zero
     * extractions is a valid, successful outcome (the source is still recorded; the organizer can
     * add items manually instead), not an error.
     */
    @Transactional
    public RequirementImportResultResponse importFromText(UUID callerUserId, UUID poolId,
                                                            ImportRequirementsRequest request) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());
        requireDraft(pool);

        RequirementSourceType sourceType = parseImportSourceType(request.sourceType());
        RequirementSource source = requirementSourceRepository.save(
                new RequirementSource(poolId, sourceType, request.rawText(), callerUserId));

        List<AiExtractionGateway.ExtractedRequirement> extracted = aiExtractionGateway.extract(request.rawText());
        List<Requirement> saved = new ArrayList<>(extracted.size());
        for (AiExtractionGateway.ExtractedRequirement e : extracted) {
            Requirement requirement = new Requirement(poolId, e.name(), e.quantityPerStudent(), e.brand(),
                    e.strictness());
            RequirementState state = e.confidence() >= CONFIDENCE_THRESHOLD
                    ? RequirementState.EXTRACTED : RequirementState.NEEDS_REVIEW;
            BigDecimal confidence = BigDecimal.valueOf(e.confidence()).setScale(3, RoundingMode.HALF_UP);
            requirement.attachExtractionSource(source.getId(), e.sourceEvidence(), confidence, state);
            saved.add(requirementRepository.save(requirement));
        }

        RequirementSourceResponse sourceResponse = toSourceResponse(source, saved.size());
        List<RequirementResponse> requirementResponses = requirementAssembler.toResponses(saved, pool);
        return new RequirementImportResultResponse(sourceResponse, requirementResponses);
    }

    /** Organizer-only (contract) — every import attempt for this pool, oldest first. */
    @Transactional(readOnly = true)
    public List<RequirementSourceResponse> listSources(UUID callerUserId, UUID poolId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());

        List<RequirementSource> sources = requirementSourceRepository.findByPoolIdOrderByCreatedAtAsc(poolId);
        if (sources.isEmpty()) {
            return List.of();
        }
        List<UUID> sourceIds = sources.stream().map(RequirementSource::getId).toList();
        Map<UUID, Long> counts = requirementRepository.countByRequirementSourceIdIn(sourceIds).stream()
                .collect(Collectors.toMap(RequirementRepository.SourceRequirementCount::getSourceId,
                        RequirementRepository.SourceRequirementCount::getTotal));
        return sources.stream()
                .map(s -> toSourceResponse(s, counts.getOrDefault(s.getId(), 0L).intValue()))
                .toList();
    }

    private RequirementSourceResponse toSourceResponse(RequirementSource source, int extractedRequirementCount) {
        return new RequirementSourceResponse(source.getId(), source.getPoolId(), source.getSourceType().name(),
                source.getRawText(), extractedRequirementCount, source.getCreatedAt());
    }

    private void requireDraft(Pool pool) {
        if (pool.getState() != PoolState.DRAFT) {
            throw new ConflictException("Pool is no longer in DRAFT — requirements are locked once confirmed");
        }
    }

    private RequirementSourceType parseImportSourceType(String raw) {
        RequirementSourceType sourceType;
        try {
            sourceType = RequirementSourceType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown sourceType: " + raw);
        }
        if (!ALLOWED_IMPORT_SOURCE_TYPES.contains(sourceType)) {
            throw new BadRequestException(
                    "sourceType must be one of PASTED_EMAIL, PASTED_PORTAL, PASTED_MESSAGE for text import");
        }
        return sourceType;
    }
}
