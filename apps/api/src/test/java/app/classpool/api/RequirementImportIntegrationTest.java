package app.classpool.api;

import app.classpool.api.dto.ClassroomCreatedResponse;
import app.classpool.api.dto.CreateClassroomRequest;
import app.classpool.api.dto.CreatePoolRequest;
import app.classpool.api.dto.ImportRequirementsRequest;
import app.classpool.api.dto.PoolDetailResponse;
import app.classpool.api.dto.PoolResponse;
import app.classpool.api.dto.RequirementImportResultResponse;
import app.classpool.api.dto.RequirementSourceResponse;
import app.classpool.api.support.AbstractIntegrationTest;
import app.classpool.api.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of Phase 11's AI-assisted import surface (PRD §3.1/§3.2), mirroring {@code
 * OrderDistributionIntegrationTest}'s structure: paste a small realistic supply list, assert the
 * created requirements' states/fields over the full HTTP flow, then confirm the pool the normal
 * way (Phase 3's existing confirm endpoint) to prove EXTRACTED/NEEDS_REVIEW requirements created
 * via this new path integrate cleanly with the existing confirm flow — zero changes needed there.
 */
class RequirementImportIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestUsers testUsers;

    @Test
    void importFromText_createsRequirements_andASourceRecord_thenConfirmSucceeds() {
        TestUsers.AuthedUser organizer = testUsers.create("importOrg@example.com", "Import Organizer");
        UUID classroomId = createClassroom(organizer, "Import School " + System.nanoTime());
        UUID poolId = createPool(organizer, classroomId);

        String rawText = String.join("\n",
                "Hi everyone,",
                "4 Elmer's glue sticks per student",
                "pencils - need about a dozen, any kind is fine",
                "Thanks, Ms. Lee");

        RequirementImportResultResponse imported = importText(organizer, poolId, "PASTED_EMAIL", rawText);

        assertThat(imported.source().sourceType()).isEqualTo("PASTED_EMAIL");
        assertThat(imported.source().rawText()).isEqualTo(rawText);
        assertThat(imported.source().extractedRequirementCount()).isEqualTo(2); // greeting/sign-off excluded
        assertThat(imported.requirements()).hasSize(2);

        var glueSticks = imported.requirements().stream()
                .filter(r -> r.name().toLowerCase().contains("glue")).findFirst().orElseThrow();
        assertThat(glueSticks.quantityPerStudent()).isEqualTo(4);
        assertThat(glueSticks.brand()).isEqualTo("Elmer's");
        assertThat(glueSticks.state()).isEqualTo("EXTRACTED"); // clean line -> confidence >= 0.85
        assertThat(glueSticks.sourceEvidence()).isEqualTo("4 Elmer's glue sticks per student");
        assertThat(glueSticks.confidence()).isNotNull();

        var pencils = imported.requirements().stream()
                .filter(r -> r.name().toLowerCase().contains("pencil")).findFirst().orElseThrow();
        assertThat(pencils.quantityPerStudent()).isEqualTo(12); // "a dozen"
        assertThat(pencils.strictness()).isEqualTo("GENERIC"); // "any kind is fine"
        assertThat(pencils.state()).isEqualTo("NEEDS_REVIEW"); // messy line -> confidence < 0.85

        // GET listRequirementSources shows this one source with its extracted count.
        List<RequirementSourceResponse> sources = listSources(organizer, poolId);
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).id()).isEqualTo(imported.source().id());
        assertThat(sources.get(0).extractedRequirementCount()).isEqualTo(2);

        // A second, unrecognizable paste is still a 201 with an empty requirements array — the
        // source is still recorded (not an error).
        RequirementImportResultResponse emptyImport = importText(organizer, poolId, "PASTED_MESSAGE",
                "Please see the attached list.\nThanks!");
        assertThat(emptyImport.requirements()).isEmpty();
        assertThat(emptyImport.source().extractedRequirementCount()).isZero();
        assertThat(listSources(organizer, poolId)).hasSize(2);

        // Confirming the pool moves both EXTRACTED and NEEDS_REVIEW requirements to CONFIRMED —
        // zero changes needed in PoolService.confirm to handle AI-imported requirements.
        PoolDetailResponse confirmed = confirm(organizer, poolId);
        assertThat(confirmed.state()).isEqualTo("OPEN_FOR_INVENTORY");
        assertThat(confirmed.requirements()).hasSize(2);
        assertThat(confirmed.requirements()).allSatisfy(r -> assertThat(r.state()).isEqualTo("CONFIRMED"));
    }

    @Test
    void importFromText_isOrganizerOnly_andRequiresDraft() {
        TestUsers.AuthedUser organizer = testUsers.create("importOrg2@example.com", "Import Organizer Two");
        TestUsers.AuthedUser nonOrganizer = testUsers.create("importParent@example.com", "Import Parent");
        UUID classroomId = createClassroom(organizer, "Import School B " + System.nanoTime());
        UUID poolId = createPool(organizer, classroomId);

        ResponseEntity<String> forbidden = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/requirement-sources", HttpMethod.POST,
                new HttpEntity<>(new ImportRequirementsRequest("PASTED_EMAIL", "4 pencils"),
                        authHeaders(nonOrganizer)),
                String.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        importText(organizer, poolId, "PASTED_EMAIL", "4 pencils per student");
        confirm(organizer, poolId);

        ResponseEntity<String> conflict = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/requirement-sources", HttpMethod.POST,
                new HttpEntity<>(new ImportRequirementsRequest("PASTED_EMAIL", "4 more pencils"),
                        authHeaders(organizer)),
                String.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ---- fixtures ----

    private UUID createClassroom(TestUsers.AuthedUser organizer, String schoolName) {
        CreateClassroomRequest request = new CreateClassroomRequest(
                null, schoolName, "2026-2027", "Grade 2", "Mr. Import", null, 20);
        ResponseEntity<ClassroomCreatedResponse> response = rest.exchange(
                baseUrl() + "/api/v1/classrooms", HttpMethod.POST, new HttpEntity<>(request, authHeaders(organizer)),
                ClassroomCreatedResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().classroom().id();
    }

    private UUID createPool(TestUsers.AuthedUser organizer, UUID classroomId) {
        ResponseEntity<PoolResponse> response = rest.exchange(
                baseUrl() + "/api/v1/classrooms/" + classroomId + "/pools", HttpMethod.POST,
                new HttpEntity<>(new CreatePoolRequest("Fall Supplies", null), authHeaders(organizer)),
                PoolResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private RequirementImportResultResponse importText(TestUsers.AuthedUser organizer, UUID poolId,
                                                         String sourceType, String rawText) {
        ResponseEntity<RequirementImportResultResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/requirement-sources", HttpMethod.POST,
                new HttpEntity<>(new ImportRequirementsRequest(sourceType, rawText), authHeaders(organizer)),
                RequirementImportResultResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private List<RequirementSourceResponse> listSources(TestUsers.AuthedUser organizer, UUID poolId) {
        ResponseEntity<RequirementSourceResponse[]> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/requirement-sources", HttpMethod.GET,
                new HttpEntity<>(authHeaders(organizer)), RequirementSourceResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return List.of(response.getBody());
    }

    private PoolDetailResponse confirm(TestUsers.AuthedUser organizer, UUID poolId) {
        ResponseEntity<PoolDetailResponse> response = rest.exchange(
                baseUrl() + "/api/v1/pools/" + poolId + "/confirm", HttpMethod.POST,
                new HttpEntity<>(authHeaders(organizer)), PoolDetailResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private HttpHeaders authHeaders(TestUsers.AuthedUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "CLASSPOOL_SESSION=" + user.sessionToken());
        return headers;
    }
}
