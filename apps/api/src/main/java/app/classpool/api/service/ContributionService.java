package app.classpool.api.service;

import app.classpool.api.domain.AppUser;
import app.classpool.api.domain.Contribution;
import app.classpool.api.domain.ContributionMode;
import app.classpool.api.domain.ContributionState;
import app.classpool.api.domain.Pool;
import app.classpool.api.domain.Requirement;
import app.classpool.api.dto.ContributionResponse;
import app.classpool.api.dto.OfferContributionRequest;
import app.classpool.api.exception.BadRequestException;
import app.classpool.api.exception.ConflictException;
import app.classpool.api.exception.ForbiddenException;
import app.classpool.api.exception.NotFoundException;
import app.classpool.api.repository.AppUserRepository;
import app.classpool.api.repository.ContributionRepository;
import app.classpool.api.repository.MembershipRepository;
import app.classpool.api.repository.RequirementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Surplus contribution pledges against a pool's requirements (PRD §5). V1 only supports {@code
 * DONATE} (Give) — {@link #offer} rejects {@code LEND}/{@code SELL}/{@code KEEP} with 400, per
 * PRD §5.1 marking them "later". Lifecycle this phase only drives {@code PLEDGED -> RECEIVED}
 * (PRD §5.4); every later state (ALLOCATED, DISTRIBUTED, the Lend return path) is a later phase's
 * concern.
 */
@Service
public class ContributionService {

    private final ContributionRepository contributionRepository;
    private final RequirementRepository requirementRepository;
    private final MembershipRepository membershipRepository;
    private final AppUserRepository appUserRepository;
    private final PoolService poolService;

    public ContributionService(ContributionRepository contributionRepository,
                                RequirementRepository requirementRepository,
                                MembershipRepository membershipRepository, AppUserRepository appUserRepository,
                                PoolService poolService) {
        this.contributionRepository = contributionRepository;
        this.requirementRepository = requirementRepository;
        this.membershipRepository = membershipRepository;
        this.appUserRepository = appUserRepository;
        this.poolService = poolService;
    }

    /**
     * A parent pledges surplus of one requirement (PRD §5.1 "Offer surplus" — starts {@code
     * PLEDGED}). The caller must hold a Membership on this pool's classroom for the specific
     * {@code studentId} in the request — the same per-student authorization boundary as {@code
     * InventoryService.setInventory} (contract) — but that student is used only for this
     * authorization check, never persisted: the {@code contribution} table's V1 migration has
     * {@code offering_parent_id}, not {@code student_id}, so the row is attributed to the caller's
     * own user id. {@code quantity} is stored exactly as given — the household's own independent
     * declaration of surplus, never clamped against their Phase 4 inventory answer (contract).
     */
    @Transactional
    public ContributionResponse offer(UUID callerUserId, UUID poolId, UUID requirementId,
                                       OfferContributionRequest request) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        membershipRepository
                .findByClassroom_IdAndParentUserIdAndStudent_Id(pool.getClassroomId(), callerUserId,
                        request.studentId())
                .orElseThrow(() -> new ForbiddenException(
                        "Caller has no Membership on this classroom for that student"));

        ContributionMode mode = parseMode(request.mode());
        if (mode != ContributionMode.DONATE) {
            throw new BadRequestException("Unsupported mode (only DONATE in V1): " + mode);
        }

        Requirement requirement = requirementRepository.findByIdAndPoolId(requirementId, poolId)
                .orElseThrow(() -> new NotFoundException("Requirement not found: " + requirementId));

        Contribution contribution = contributionRepository.save(
                new Contribution(requirement.getId(), callerUserId, request.quantity(), mode));
        return toResponse(contribution, requirement, null);
    }

    /** The caller's own contributions across this pool (contract) — no {@code
     *  offeringParentDisplayName}, it would just be the caller's own name. */
    @Transactional(readOnly = true)
    public List<ContributionResponse> getMine(UUID callerUserId, UUID poolId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireMembership(callerUserId, pool.getClassroomId());

        List<Requirement> requirements = requirementRepository.findByPoolIdOrderByCreatedAtAsc(poolId);
        if (requirements.isEmpty()) {
            return List.of();
        }
        Map<UUID, Requirement> requirementsById = toMapById(requirements);
        List<UUID> requirementIds = requirements.stream().map(Requirement::getId).toList();
        List<Contribution> contributions = contributionRepository
                .findByOfferingParentIdAndRequirementIdInOrderByCreatedAtAsc(callerUserId, requirementIds);
        return contributions.stream()
                .map(c -> toResponse(c, requirementsById.get(c.getRequirementId()), null))
                .toList();
    }

    /**
     * The offering parent withdraws their own pledge (contract) — only while {@code state =
     * PLEDGED}. This is the parent undoing their own action, not an organizer action: 403 for
     * anyone else, including the organizer, even though the organizer can see the same row via
     * {@link #listForOrganizer}.
     */
    @Transactional
    public void withdraw(UUID callerUserId, UUID poolId, UUID contributionId) {
        Contribution contribution = getInPoolOrThrow(poolId, contributionId);
        if (!contribution.getOfferingParentId().equals(callerUserId)) {
            throw new ForbiddenException("Caller does not own this contribution");
        }
        if (contribution.getState() != ContributionState.PLEDGED) {
            throw new ConflictException("Contribution is already RECEIVED — cannot withdraw");
        }
        contributionRepository.delete(contribution);
    }

    /**
     * Every contribution pledged/received across the pool (contract) — organizer/co-organizer
     * only, the one place {@code offeringParentDisplayName} is populated (PRD §5.3's privacy
     * model: the organizer can see contributor identity, other parents never do).
     */
    @Transactional(readOnly = true)
    public List<ContributionResponse> listForOrganizer(UUID callerUserId, UUID poolId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());

        List<Requirement> requirements = requirementRepository.findByPoolIdOrderByCreatedAtAsc(poolId);
        if (requirements.isEmpty()) {
            return List.of();
        }
        Map<UUID, Requirement> requirementsById = toMapById(requirements);
        List<UUID> requirementIds = requirements.stream().map(Requirement::getId).toList();
        List<Contribution> contributions = contributionRepository
                .findByRequirementIdInOrderByCreatedAtAsc(requirementIds);
        if (contributions.isEmpty()) {
            return List.of();
        }

        List<UUID> offeringParentIds = contributions.stream().map(Contribution::getOfferingParentId).distinct()
                .toList();
        Map<UUID, String> displayNamesByUserId = appUserRepository.findAllById(offeringParentIds).stream()
                .collect(Collectors.toMap(AppUser::getId, AppUser::getDisplayName));

        return contributions.stream()
                .map(c -> toResponse(c, requirementsById.get(c.getRequirementId()),
                        displayNamesByUserId.get(c.getOfferingParentId())))
                .toList();
    }

    /**
     * Organizer confirms a pledge has physically arrived (PRD §5.4: {@code PLEDGED -> RECEIVED}).
     * Organizer/co-organizer only (contract); 409 if already {@code RECEIVED}.
     */
    @Transactional
    public ContributionResponse markReceived(UUID callerUserId, UUID poolId, UUID contributionId) {
        Pool pool = poolService.getEntityOrThrow(poolId);
        poolService.requireOrganizer(callerUserId, pool.getClassroomId());

        Contribution contribution = getInPoolOrThrow(poolId, contributionId);
        if (contribution.getState() == ContributionState.RECEIVED) {
            throw new ConflictException("Contribution is already RECEIVED");
        }
        contribution.markReceived();
        contributionRepository.save(contribution);

        Requirement requirement = requirementRepository.findById(contribution.getRequirementId())
                .orElseThrow(() -> new NotFoundException("Requirement not found: " + contribution.getRequirementId()));
        return toResponse(contribution, requirement, null);
    }

    /** Scoped fetch: only returns a hit if {@code contributionId} belongs to one of this pool's
     *  requirements, matching {@code RequirementService.getEntityOrThrow}'s cross-tenant guard. */
    private Contribution getInPoolOrThrow(UUID poolId, UUID contributionId) {
        List<UUID> requirementIds = requirementRepository.findByPoolIdOrderByCreatedAtAsc(poolId).stream()
                .map(Requirement::getId).toList();
        return contributionRepository.findByIdAndRequirementIdIn(contributionId, requirementIds)
                .orElseThrow(() -> new NotFoundException("Contribution not found: " + contributionId));
    }

    private static Map<UUID, Requirement> toMapById(List<Requirement> requirements) {
        return requirements.stream().collect(Collectors.toMap(Requirement::getId, r -> r));
    }

    private ContributionMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return ContributionMode.DONATE;
        }
        try {
            return ContributionMode.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unsupported mode (only DONATE in V1): " + raw);
        }
    }

    /** {@code studentId}/{@code studentFirstName} are always null — see {@link ContributionResponse}'s
     *  Javadoc for why. */
    private static ContributionResponse toResponse(Contribution contribution, Requirement requirement,
                                                     String offeringParentDisplayName) {
        return new ContributionResponse(
                contribution.getId(),
                contribution.getRequirementId(),
                requirement.getName(),
                null,
                null,
                offeringParentDisplayName,
                contribution.getQuantity(),
                contribution.getMode().name(),
                contribution.getState().name(),
                contribution.getCreatedAt());
    }
}
