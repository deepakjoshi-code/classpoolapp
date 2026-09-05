package app.classpool.api.service;

import app.classpool.api.domain.RequirementStrictness;
import app.classpool.api.service.AiExtractionGateway.ExtractedRequirement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the heuristic directly, independent of HTTP/persistence, against a realistic
 * multi-line pasted email — mixing clean lines, messy lines, an explicit "must be exactly" line,
 * and a couple of non-item lines (greeting/sign-off), per this phase's task spec.
 */
class StubAiExtractionGatewayTest {

    private final StubAiExtractionGateway gateway = new StubAiExtractionGateway();

    private static final String PASTED_EMAIL = String.join("\n",
            "Hi everyone,",
            "Please pick up the following supplies before the first day of school:",
            "4 Elmer's glue sticks per student",
            "- 2 boxes of tissues",
            "Pencils x12 (any brand)",
            "pencils - need about a dozen, any kind is fine",
            "5 highlighters - brand: Crayola",
            "Notebooks - must be exactly the Mead brand, 2 per student",
            "3 pocket folders (pack of 12 preferred)",
            "Thanks so much,",
            "Ms. Smith"
    );

    @Test
    void extract_findsOneEntryPerRecognizableSupplyListLine() {
        List<ExtractedRequirement> results = gateway.extract(PASTED_EMAIL);

        // 11 lines in, 4 are non-item (greeting, instruction sentence, sign-off, signature name) ->
        // exactly 7 real candidate lines.
        assertThat(results).hasSize(7);
    }

    @Test
    void extract_producesNothing_forGreetingAndSignOffLines() {
        List<ExtractedRequirement> results = gateway.extract(PASTED_EMAIL);

        assertThat(results).noneMatch(r -> r.sourceEvidence().toLowerCase().contains("hi everyone"));
        assertThat(results).noneMatch(r -> r.sourceEvidence().toLowerCase().contains("thanks so much"));
        assertThat(results).noneMatch(r -> r.sourceEvidence().equals("Ms. Smith"));
        assertThat(results).noneMatch(r -> r.sourceEvidence().toLowerCase().contains("please pick up"));
    }

    @Test
    void extract_producesNothing_forBlankOrEmptyInput() {
        assertThat(gateway.extract("")).isEmpty();
        assertThat(gateway.extract("   \n  \n")).isEmpty();
        assertThat(gateway.extract(null)).isEmpty();
    }

    @Test
    void extract_readsExplicitQuantityAndBrand_fromACleanLine() {
        ExtractedRequirement glueSticks = find(gateway.extract(PASTED_EMAIL), "glue");

        assertThat(glueSticks.quantityPerStudent()).isEqualTo(4);
        assertThat(glueSticks.brand()).isEqualTo("Elmer's");
        assertThat(glueSticks.name().toLowerCase()).contains("glue");
        assertThat(glueSticks.strictness()).isEqualTo(RequirementStrictness.EQUIVALENT_ALLOWED);
        assertThat(glueSticks.sourceEvidence()).isEqualTo("4 Elmer's glue sticks per student");
    }

    @Test
    void extract_readsQuantityFromAContainerPhrase_withNoBrand() {
        ExtractedRequirement tissues = find(gateway.extract(PASTED_EMAIL), "tissues");

        assertThat(tissues.quantityPerStudent()).isEqualTo(2);
        assertThat(tissues.brand()).isNull();
        assertThat(tissues.strictness()).isEqualTo(RequirementStrictness.EQUIVALENT_ALLOWED);
    }

    @Test
    void extract_infersGenericStrictness_fromAnyBrandPhrase_evenWithAnXQuantityFormat() {
        ExtractedRequirement pencils = find(gateway.extract(PASTED_EMAIL), "Pencils x12");

        assertThat(pencils.quantityPerStudent()).isEqualTo(12);
        assertThat(pencils.brand()).isNull();
        assertThat(pencils.strictness()).isEqualTo(RequirementStrictness.GENERIC);
    }

    @Test
    void extract_parsesAWordNumberQuantity_fromAMessyLine_withGenericStrictness() {
        ExtractedRequirement messyPencils = find(gateway.extract(PASTED_EMAIL), "need about a dozen");

        assertThat(messyPencils.quantityPerStudent()).isEqualTo(12); // "a dozen"
        assertThat(messyPencils.strictness()).isEqualTo(RequirementStrictness.GENERIC); // "any kind is fine"
    }

    @Test
    void extract_readsAnExplicitBrandLabel() {
        ExtractedRequirement highlighters = find(gateway.extract(PASTED_EMAIL), "highlighters");

        assertThat(highlighters.quantityPerStudent()).isEqualTo(5);
        assertThat(highlighters.brand()).isEqualTo("Crayola");
        assertThat(highlighters.strictness()).isEqualTo(RequirementStrictness.EQUIVALENT_ALLOWED);
    }

    @Test
    void extract_infersExactStrictness_fromMustBeExactlyPhrase_andStillFindsTheBrand() {
        ExtractedRequirement notebooks = find(gateway.extract(PASTED_EMAIL), "Notebooks");

        assertThat(notebooks.quantityPerStudent()).isEqualTo(2);
        assertThat(notebooks.brand()).isEqualTo("Mead");
        assertThat(notebooks.strictness()).isEqualTo(RequirementStrictness.EXACT);
    }

    @Test
    void extract_takesTheFirstIntegerFound_whenALineHasMoreThanOneNumber() {
        ExtractedRequirement folders = find(gateway.extract(PASTED_EMAIL), "pocket folders");

        assertThat(folders.quantityPerStudent()).isEqualTo(3); // not 12, the pack-size number
    }

    @Test
    void extract_neverClaimsFullCertainty() {
        for (ExtractedRequirement r : gateway.extract(PASTED_EMAIL)) {
            assertThat(r.confidence()).isLessThan(1.0);
            assertThat(r.confidence()).isGreaterThanOrEqualTo(0.4);
        }
    }

    @Test
    void extract_ratesCleanExplicitQuantityLines_moreConfidentThanAmbiguousOnes() {
        List<ExtractedRequirement> results = gateway.extract(PASTED_EMAIL);
        double cleanConfidence = find(results, "glue").confidence();
        double wordNumberConfidence = find(results, "need about a dozen").confidence();
        double multipleNumbersConfidence = find(results, "pocket folders").confidence();

        assertThat(cleanConfidence).isGreaterThan(wordNumberConfidence);
        assertThat(cleanConfidence).isGreaterThan(multipleNumbersConfidence);
    }

    @Test
    void extract_neverFabricatesARequirement_forALineWithNoPlausibleQuantity() {
        List<ExtractedRequirement> results = gateway.extract(
                "Backpack recommended but not required\nHave a great year!");

        assertThat(results).isEmpty();
    }

    private static ExtractedRequirement find(List<ExtractedRequirement> results, String evidenceContains) {
        Optional<ExtractedRequirement> match = results.stream()
                .filter(r -> r.sourceEvidence().contains(evidenceContains))
                .findFirst();
        assertThat(match).as("expected a result whose evidence contains '%s'", evidenceContains).isPresent();
        return match.get();
    }
}
