package app.classpool.api.service;

import app.classpool.api.domain.Household;
import app.classpool.api.domain.Membership;
import app.classpool.api.dto.HouseholdDashboardResponse;
import app.classpool.api.dto.MembershipResponse;
import app.classpool.api.exception.NotFoundException;
import app.classpool.api.repository.HouseholdRepository;
import app.classpool.api.repository.MembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class HouseholdService {

    private final HouseholdRepository householdRepository;
    private final MembershipRepository membershipRepository;
    private final MembershipAssembler membershipAssembler;

    public HouseholdService(HouseholdRepository householdRepository, MembershipRepository membershipRepository,
                             MembershipAssembler membershipAssembler) {
        this.householdRepository = householdRepository;
        this.membershipRepository = membershipRepository;
        this.membershipAssembler = membershipAssembler;
    }

    @Transactional(readOnly = true)
    public HouseholdDashboardResponse getDashboard(UUID callerUserId) {
        Household household = householdRepository.findByPrimaryParentId(callerUserId)
                .orElseThrow(() -> new NotFoundException("No household yet — join a classroom first"));
        // Every membership this user (the household's sole parent in the V1 data model — see
        // README) created: their own PARENT joins plus any ORGANIZER grants, across every
        // classroom (PRD §12 multi-class HOME update).
        List<Membership> memberships = membershipRepository.findByParentUserIdOrderByCreatedAtAsc(callerUserId);
        List<MembershipResponse> membershipResponses = membershipAssembler.toResponses(memberships);
        return new HouseholdDashboardResponse(household.getId(), membershipResponses);
    }
}
