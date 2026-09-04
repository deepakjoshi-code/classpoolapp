package app.classpool.api.service;

import app.classpool.api.domain.Classroom;
import app.classpool.api.domain.Household;
import app.classpool.api.domain.Invite;
import app.classpool.api.domain.InviteChannel;
import app.classpool.api.domain.Membership;
import app.classpool.api.domain.MembershipRole;
import app.classpool.api.domain.Student;
import app.classpool.api.dto.InvitePreviewResponse;
import app.classpool.api.dto.InviteResponse;
import app.classpool.api.dto.MembershipResponse;
import app.classpool.api.exception.ForbiddenException;
import app.classpool.api.exception.NotFoundException;
import app.classpool.api.exception.UnauthorizedException;
import app.classpool.api.repository.InviteRepository;
import app.classpool.api.repository.MembershipRepository;
import app.classpool.api.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

@Service
public class InviteService {

    // Excludes ambiguous characters (0/O, 1/I/L) — these tokens are meant to be typed/read off a
    // QR fallback or shared verbally, per PRD §2.2's `classpool.app/join/7H2KQ` example.
    private static final String TOKEN_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int TOKEN_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final InviteRepository inviteRepository;
    private final ClassroomService classroomService;
    private final MembershipRepository membershipRepository;
    private final HouseholdService householdService;
    private final StudentRepository studentRepository;
    private final ClassroomAssembler classroomAssembler;
    private final MembershipAssembler membershipAssembler;
    private final PoolGateway poolGateway;
    private final String webBaseUrl;

    public InviteService(InviteRepository inviteRepository, ClassroomService classroomService,
                          MembershipRepository membershipRepository, HouseholdService householdService,
                          StudentRepository studentRepository, ClassroomAssembler classroomAssembler,
                          MembershipAssembler membershipAssembler, PoolGateway poolGateway,
                          @Value("${classpool.web-base-url}") String webBaseUrl) {
        this.inviteRepository = inviteRepository;
        this.classroomService = classroomService;
        this.membershipRepository = membershipRepository;
        this.householdService = householdService;
        this.studentRepository = studentRepository;
        this.classroomAssembler = classroomAssembler;
        this.membershipAssembler = membershipAssembler;
        this.poolGateway = poolGateway;
        this.webBaseUrl = webBaseUrl;
    }

    @Transactional
    public InviteResponse create(UUID callerUserId, UUID classroomId, InviteChannel channel) {
        // Organizer/co-organizer only (contract). A plain PARENT membership does not authorize
        // creating invites. Shared with PoolService/RequirementService — see
        // MembershipRepository.hasOrganizerRole's Javadoc.
        if (!membershipRepository.hasOrganizerRole(classroomId, callerUserId)) {
            throw new ForbiddenException("Caller is not an organizer on this classroom");
        }
        // Confirms the classroom exists (404 semantics belong to a get-classroom call; here a
        // missing classroom simply cannot have an organizer membership, so the check above already
        // covers it).
        String token = generateUniqueToken();
        Invite invite = inviteRepository.save(new Invite(classroomId, token, channel, callerUserId));
        return toResponse(invite);
    }

    @Transactional(readOnly = true)
    public InvitePreviewResponse preview(String token) {
        Invite invite = inviteRepository.findByToken(token)
                .orElseThrow(() -> new NotFoundException("Invite not found or expired"));
        Classroom classroom = classroomService.getEntityOrThrow(invite.getClassroomId());
        long membersJoined = membershipRepository.countByClassroom_Id(invite.getClassroomId());
        invite.setOpenedAt(Instant.now());
        inviteRepository.save(invite);
        return new InvitePreviewResponse(classroomAssembler.toResponse(classroom), membersJoined,
                classroom.getStudentCountEstimate());
    }

    @Transactional
    public MembershipResponse join(UUID callerUserId, String token, String studentFirstName) {
        if (callerUserId == null) {
            throw new UnauthorizedException("Not authenticated");
        }
        Invite invite = inviteRepository.findByToken(token)
                .orElseThrow(() -> new NotFoundException("Invite not found or expired"));
        Classroom classroom = classroomService.getEntityOrThrow(invite.getClassroomId());

        Household household = householdService.getOrCreateHousehold(callerUserId);
        Student student = studentRepository.save(new Student(household.getId(), studentFirstName.trim()));

        boolean lateJoin = poolGateway.hasPoolPastOpenForContributions(classroom.getId());

        Membership membership = membershipRepository.save(
                new Membership(classroom, callerUserId, student, MembershipRole.PARENT, lateJoin));

        invite.setConvertedAt(Instant.now());
        invite.setConvertedUserId(callerUserId);
        inviteRepository.save(invite);

        return membershipAssembler.toResponse(membership);
    }

    private InviteResponse toResponse(Invite invite) {
        String joinUrl = webBaseUrl + "/join/" + invite.getToken();
        String channel = invite.getChannel() == null ? null : invite.getChannel().name();
        return new InviteResponse(invite.getToken(), joinUrl, joinUrl, channel);
    }

    private String generateUniqueToken() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = randomToken();
            if (inviteRepository.findByToken(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique invite token");
    }

    private static String randomToken() {
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            sb.append(TOKEN_ALPHABET.charAt(RANDOM.nextInt(TOKEN_ALPHABET.length())));
        }
        return sb.toString();
    }
}
