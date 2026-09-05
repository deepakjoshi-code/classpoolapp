package app.classpool.api.service;

import app.classpool.api.domain.Pool;
import app.classpool.api.domain.PoolState;
import app.classpool.api.domain.Requirement;
import app.classpool.api.domain.RequirementSource;
import app.classpool.api.domain.RequirementSourceType;
import app.classpool.api.domain.RequirementState;
import app.classpool.api.dto.ImportRequirementsRequest;
import app.classpool.api.dto.RequirementImportResultResponse;
import app.classpool.api.dto.RequirementSourceResponse;
import app.classpool.api.exception.BadRequestException;
import app.classpool.api.exception.ConflictException;
import app.classpool.api.exception.ForbiddenException;
import app.classpool.api.repository.MembershipRepository;
import app.classpool.api.repository.PoolRepository;
import app.classpool.api.repository.RequirementRepository;
import app.classpool.api.repository.RequirementSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RequirementImportServiceTest {

    @Mock
    private RequirementSourceRepository requirementSourceRepository;
    @Mock
    private RequirementRepository requirementRepository;
    @Mock
    private PoolRepository poolRepository;
    @Mock
    private MembershipRepository membershipRepository;
    @Mock
    private AiExtractionGateway aiExtractionGateway;

    private RequirementImportService requirementImportService;

    private final UUID callerId = UUID.randomUUID();
    private final UUID classroomId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // PoolService exercised for real (same pattern as ContributionServiceTest) — only its
        // repository collaborators are mocked.
        PoolAssembler poolAssembler = new PoolAssembler(requirementRepository);
        RequirementAssembler requirementAssembler = new RequirementAssembler();
        PoolService poolService = new PoolService(poolRepository, requirementRepository, membershipRepository,
                poolAssembler, requirementAssembler);
        requirementImportService = new RequirementImportService(requirementSourceRepository, requirementRepository,
                poolService, aiExtractionGateway, requirementAssembler);
    }

    // ---- importFromText ----

    @Test
    void importFromText_createsASource_andOneRequirementPerExtraction() {
        Pool pool = newPool(PoolState.DRAFT);
        when(poolRepository.findById(pool.getId())).thenReturn(java.util.Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(requirementSourceRepository.save(any(RequirementSource.class))).thenAnswer(inv -> {
            RequirementSource s = inv.getArgument(0);
            setField(s, "id", UUID.randomUUID());
            setField(s, "createdAt", Instant.now());
            return s;
        });
        when(aiExtractionGateway.extract("4 glue sticks per student\n2 boxes of tissues")).thenReturn(List.of(
                new AiExtractionGateway.ExtractedRequirement("glue sticks", 4, null,
                        app.classpool.api.domain.RequirementStrictness.EQUIVALENT_ALLOWED,
                        "4 glue sticks per student", 0.92),
                new AiExtractionGateway.ExtractedRequirement("tissues", 2, null,
                        app.classpool.api.domain.RequirementStrictness.EQUIVALENT_ALLOWED,
                        "2 boxes of tissues", 0.6)
        ));
        when(requirementRepository.save(any(Requirement.class))).thenAnswer(inv -> {
            Requirement r = inv.getArgument(0);
            setField(r, "id", UUID.randomUUID());
            setField(r, "createdAt", Instant.now());
            setField(r, "updatedAt", Instant.now());
            return r;
        });

        RequirementImportResultResponse result = requirementImportService.importFromText(callerId, pool.getId(),
                new ImportRequirementsRequest("PASTED_EMAIL", "4 glue sticks per student\n2 boxes of tissues"));

        assertThat(result.source().sourceType()).isEqualTo("PASTED_EMAIL");
        assertThat(result.source().extractedRequirementCount()).isEqualTo(2);
        assertThat(result.requirements()).hasSize(2);

        ArgumentCaptor<Requirement> captor = ArgumentCaptor.forClass(Requirement.class);
        verify(requirementRepository, times(2)).save(captor.capture());
        List<Requirement> saved = captor.getAllValues();
        assertThat(saved.get(0).getState()).isEqualTo(RequirementState.EXTRACTED); // 0.92 >= 0.85
        assertThat(saved.get(1).getState()).isEqualTo(RequirementState.NEEDS_REVIEW); // 0.6 < 0.85
        assertThat(saved.get(0).getSourceEvidence()).isEqualTo("4 glue sticks per student");
        assertThat(saved.get(0).getConfidence()).isEqualByComparingTo("0.920");
    }

    @Test
    void importFromText_atExactlyTheThreshold_isExtractedNotNeedsReview() {
        Pool pool = newPool(PoolState.DRAFT);
        when(poolRepository.findById(pool.getId())).thenReturn(java.util.Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(requirementSourceRepository.save(any(RequirementSource.class))).thenAnswer(inv -> {
            RequirementSource s = inv.getArgument(0);
            setField(s, "id", UUID.randomUUID());
            setField(s, "createdAt", Instant.now());
            return s;
        });
        when(aiExtractionGateway.extract("x")).thenReturn(List.of(
                new AiExtractionGateway.ExtractedRequirement("pencils", 1, null,
                        app.classpool.api.domain.RequirementStrictness.EQUIVALENT_ALLOWED, "x", 0.85)));
        when(requirementRepository.save(any(Requirement.class))).thenAnswer(inv -> {
            Requirement r = inv.getArgument(0);
            setField(r, "id", UUID.randomUUID());
            setField(r, "createdAt", Instant.now());
            setField(r, "updatedAt", Instant.now());
            return r;
        });

        requirementImportService.importFromText(callerId, pool.getId(),
                new ImportRequirementsRequest("PASTED_EMAIL", "x"));

        ArgumentCaptor<Requirement> captor = ArgumentCaptor.forClass(Requirement.class);
        verify(requirementRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(RequirementState.EXTRACTED); // 0.85 is inclusive
    }

    @Test
    void importFromText_justBelowTheThreshold_isNeedsReview() {
        Pool pool = newPool(PoolState.DRAFT);
        when(poolRepository.findById(pool.getId())).thenReturn(java.util.Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(requirementSourceRepository.save(any(RequirementSource.class))).thenAnswer(inv -> {
            RequirementSource s = inv.getArgument(0);
            setField(s, "id", UUID.randomUUID());
            setField(s, "createdAt", Instant.now());
            return s;
        });
        when(aiExtractionGateway.extract("x")).thenReturn(List.of(
                new AiExtractionGateway.ExtractedRequirement("pencils", 1, null,
                        app.classpool.api.domain.RequirementStrictness.EQUIVALENT_ALLOWED, "x", 0.849)));
        when(requirementRepository.save(any(Requirement.class))).thenAnswer(inv -> {
            Requirement r = inv.getArgument(0);
            setField(r, "id", UUID.randomUUID());
            setField(r, "createdAt", Instant.now());
            setField(r, "updatedAt", Instant.now());
            return r;
        });

        requirementImportService.importFromText(callerId, pool.getId(),
                new ImportRequirementsRequest("PASTED_EMAIL", "x"));

        ArgumentCaptor<Requirement> captor = ArgumentCaptor.forClass(Requirement.class);
        verify(requirementRepository).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(RequirementState.NEEDS_REVIEW);
    }

    @Test
    void importFromText_stillSucceeds_withAnEmptyRequirementsArray_whenExtractionFindsNothing() {
        Pool pool = newPool(PoolState.DRAFT);
        when(poolRepository.findById(pool.getId())).thenReturn(java.util.Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(requirementSourceRepository.save(any(RequirementSource.class))).thenAnswer(inv -> {
            RequirementSource s = inv.getArgument(0);
            setField(s, "id", UUID.randomUUID());
            setField(s, "createdAt", Instant.now());
            return s;
        });
        when(aiExtractionGateway.extract("Hi everyone,")).thenReturn(List.of());

        RequirementImportResultResponse result = requirementImportService.importFromText(callerId, pool.getId(),
                new ImportRequirementsRequest("PASTED_EMAIL", "Hi everyone,"));

        assertThat(result.source()).isNotNull();
        assertThat(result.source().extractedRequirementCount()).isZero();
        assertThat(result.requirements()).isEmpty();
        verify(requirementRepository, never()).save(any());
    }

    @Test
    void importFromText_throwsForbidden_forANonOrganizer() {
        Pool pool = newPool(PoolState.DRAFT);
        when(poolRepository.findById(pool.getId())).thenReturn(java.util.Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> requirementImportService.importFromText(callerId, pool.getId(),
                new ImportRequirementsRequest("PASTED_EMAIL", "4 glue sticks")))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(requirementSourceRepository, aiExtractionGateway);
    }

    @Test
    void importFromText_throwsConflict_whenPoolIsNotDraft() {
        Pool pool = newPool(PoolState.OPEN_FOR_INVENTORY);
        when(poolRepository.findById(pool.getId())).thenReturn(java.util.Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);

        assertThatThrownBy(() -> requirementImportService.importFromText(callerId, pool.getId(),
                new ImportRequirementsRequest("PASTED_EMAIL", "4 glue sticks")))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(requirementSourceRepository, aiExtractionGateway);
    }

    @Test
    void importFromText_throwsBadRequest_forASourceTypeNotAllowedForTextImport() {
        Pool pool = newPool(PoolState.DRAFT);
        when(poolRepository.findById(pool.getId())).thenReturn(java.util.Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);

        assertThatThrownBy(() -> requirementImportService.importFromText(callerId, pool.getId(),
                new ImportRequirementsRequest("PDF", "some text")))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(requirementSourceRepository, aiExtractionGateway);
    }

    @Test
    void importFromText_throwsBadRequest_forAnUnknownSourceType() {
        Pool pool = newPool(PoolState.DRAFT);
        when(poolRepository.findById(pool.getId())).thenReturn(java.util.Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);

        assertThatThrownBy(() -> requirementImportService.importFromText(callerId, pool.getId(),
                new ImportRequirementsRequest("NOT_A_TYPE", "some text")))
                .isInstanceOf(BadRequestException.class);
    }

    // ---- listSources ----

    @Test
    void listSources_returnsEachSourceWithItsOwnExtractedCount() {
        Pool pool = newPool(PoolState.DRAFT);
        RequirementSource source1 = newSource(pool.getId(), RequirementSourceType.PASTED_EMAIL);
        RequirementSource source2 = newSource(pool.getId(), RequirementSourceType.PASTED_PORTAL);

        when(poolRepository.findById(pool.getId())).thenReturn(java.util.Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(requirementSourceRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId()))
                .thenReturn(List.of(source1, source2));
        when(requirementRepository.countByRequirementSourceIdIn(List.of(source1.getId(), source2.getId())))
                .thenReturn(List.of(countOf(source1.getId(), 3), countOf(source2.getId(), 0)));

        List<RequirementSourceResponse> responses = requirementImportService.listSources(callerId, pool.getId());

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).extractedRequirementCount()).isEqualTo(3);
        assertThat(responses.get(1).extractedRequirementCount()).isEqualTo(0);
    }

    @Test
    void listSources_returnsEmptyList_whenNoSourcesExistYet() {
        Pool pool = newPool(PoolState.DRAFT);
        when(poolRepository.findById(pool.getId())).thenReturn(java.util.Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(true);
        when(requirementSourceRepository.findByPoolIdOrderByCreatedAtAsc(pool.getId())).thenReturn(List.of());

        List<RequirementSourceResponse> responses = requirementImportService.listSources(callerId, pool.getId());

        assertThat(responses).isEmpty();
        verifyNoInteractions(requirementRepository);
    }

    @Test
    void listSources_throwsForbidden_forANonOrganizer() {
        Pool pool = newPool(PoolState.DRAFT);
        when(poolRepository.findById(pool.getId())).thenReturn(java.util.Optional.of(pool));
        when(membershipRepository.hasOrganizerRole(classroomId, callerId)).thenReturn(false);

        assertThatThrownBy(() -> requirementImportService.listSources(callerId, pool.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- fixtures ----

    private Pool newPool(PoolState state) {
        Pool pool = new Pool(classroomId, "Fall Supplies", "SUPPLIES");
        setField(pool, "id", UUID.randomUUID());
        setField(pool, "createdAt", Instant.now());
        pool.setState(state);
        return pool;
    }

    private static RequirementSource newSource(UUID poolId, RequirementSourceType type) {
        RequirementSource source = new RequirementSource(poolId, type, "raw text", UUID.randomUUID());
        setField(source, "id", UUID.randomUUID());
        setField(source, "createdAt", Instant.now());
        return source;
    }

    private static RequirementRepository.SourceRequirementCount countOf(UUID sourceId, long total) {
        return new RequirementRepository.SourceRequirementCount() {
            @Override
            public UUID getSourceId() {
                return sourceId;
            }

            @Override
            public long getTotal() {
                return total;
            }
        };
    }

    private static void setField(Object entity, String fieldName, Object value) {
        try {
            var field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
