package app.classpool.api.service;

import app.classpool.api.domain.Household;
import app.classpool.api.repository.HouseholdRepository;
import app.classpool.api.repository.MembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * getOrCreateHousehold is the fix for a real bug found in live integration testing: an organizer
 * who creates a classroom without also joining it as a parent had no Household, so
 * /household/dashboard 404'd for them. ClassroomService.create and InviteService.join both call
 * this now instead of each doing their own get-or-create — these tests cover the shared logic
 * itself once, rather than duplicating it at every call site.
 */
@ExtendWith(MockitoExtension.class)
class HouseholdServiceTest {

    @Mock
    private HouseholdRepository householdRepository;
    @Mock
    private MembershipRepository membershipRepository;
    @Mock
    private MembershipAssembler membershipAssembler;

    private HouseholdService householdService;

    private final UUID callerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        householdService = new HouseholdService(householdRepository, membershipRepository, membershipAssembler);
    }

    @Test
    void getOrCreateHousehold_returnsExisting_whenCallerAlreadyHasOne() {
        Household existing = new Household(callerId);
        when(householdRepository.findByPrimaryParentId(callerId)).thenReturn(Optional.of(existing));

        Household result = householdService.getOrCreateHousehold(callerId);

        assertThat(result).isSameAs(existing);
        verify(householdRepository, never()).save(any(Household.class));
    }

    @Test
    void getOrCreateHousehold_createsOne_whenCallerHasNoneYet() {
        when(householdRepository.findByPrimaryParentId(callerId)).thenReturn(Optional.empty());
        Household created = new Household(callerId);
        when(householdRepository.save(any(Household.class))).thenReturn(created);

        Household result = householdService.getOrCreateHousehold(callerId);

        assertThat(result).isSameAs(created);
        verify(householdRepository).save(any(Household.class));
    }
}
