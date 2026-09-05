package app.classpool.api.service;

import app.classpool.api.domain.RequirementStrictness;

import java.util.List;

/**
 * Outbound AI document-understanding boundary (PRD §3.1/§3.2/§15's AI boundary table: "AI may
 * interpret messy pasted text into structured candidate requirements... AI may interpret messy
 * text but must never silently invent a requirement"). {@link StubAiExtractionGateway} is the only
 * implementation wired up in this environment — there is no Anthropic API key provisioned for this
 * Spring Boot app to call at runtime (a separate concern entirely from the coding assistant's own
 * model access), matching {@code StripeGateway}'s exact "real external dependency this sandbox has
 * no credentials for" posture (see README's Phase 9 notes).
 *
 * <p>This is a real, load-bearing architectural boundary, not a convenience shim: a later phase
 * swaps in a real implementation backed by a Claude API call — e.g. the Anthropic Java SDK, with
 * the {@link ExtractedRequirement} shape below expressed as a tool-use (function-calling) schema so
 * the model returns structured JSON rather than free text — per PRD §15's document-understanding
 * step, and nothing in {@code RequirementImportService}, any controller, or the OpenAPI contract
 * changes. See README's "AI ingestion (Phase 11)" notes for the full stub-vs-real boundary
 * discussion and this heuristic's honest limitations.
 */
public interface AiExtractionGateway {

    /**
     * Parses free-form pasted text (a forwarded email, school-portal copy/paste, or message) into
     * zero or more candidate requirements. Must never fabricate a requirement that isn't backed by
     * actual text in {@code rawText} — an empty result for text with nothing recognizable is a
     * valid, expected outcome (PRD §15's AI safety rule), not a failure.
     *
     * @param rawText the organizer-pasted text, verbatim
     * @return one entry per line recognized as a supply-list item; never {@code null}
     */
    List<ExtractedRequirement> extract(String rawText);

    /**
     * One candidate requirement extracted from a single line of {@code rawText}.
     *
     * @param name               the item name, with quantity/unit/filler words stripped
     * @param quantityPerStudent the per-student quantity found in the line
     * @param brand              a specific brand name if one was actually present in the text,
     *                           else {@code null} — never guessed
     * @param strictness         {@link RequirementStrictness#GENERIC} when the line says "any
     *                           brand"/"generic" (etc.), {@link RequirementStrictness#EXACT} when
     *                           it says "exactly"/"must be" (etc.), otherwise {@link
     *                           RequirementStrictness#EQUIVALENT_ALLOWED}
     * @param sourceEvidence     the original line, verbatim (PRD §3.2's "retained verbatim quote")
     * @param confidence         a heuristic confidence in {@code [0.0, 1.0)} — never claims 1.0
     *                           certainty; lower for ambiguous lines (no explicit quantity found,
     *                           more than one plausible number/brand in the line, an unusually
     *                           short or long line)
     */
    record ExtractedRequirement(String name, int quantityPerStudent, String brand,
                                 RequirementStrictness strictness, String sourceEvidence, double confidence) {
    }
}
