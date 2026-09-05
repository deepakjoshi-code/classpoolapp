package app.classpool.api.service;

import app.classpool.api.domain.RequirementStrictness;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A genuine, deterministic, line-by-line text-parsing heuristic — <strong>not</strong> an LLM. Never
 * makes a network call, and never fabricates a requirement that isn't backed by actual text in the
 * input (PRD §15's AI safety rule): a line is only ever turned into an {@link ExtractedRequirement}
 * if it contains a plausible quantity signal and a leftover item name after quantity/filler/brand
 * words are stripped from it. Swap for a real Claude-API-backed {@link AiExtractionGateway} when
 * credentials are available — nothing in {@code RequirementImportService}, any controller, or the
 * contract needs to change; see {@link AiExtractionGateway}'s Javadoc and README's "AI ingestion
 * (Phase 11)" notes for the full boundary discussion and this heuristic's honest limitations.
 *
 * <h2>The algorithm, per line</h2>
 * <ol>
 *   <li>Strip a leading bullet/dash marker; reject lines that are too short or read as a
 *       greeting/sign-off (e.g. "Hi everyone,", "Thanks, Ms. Smith") outright.
 *   <li>Find a quantity: the first explicit digit in the line (e.g. "4", "x12"), or — failing
 *       that — a small dictionary of quantity <em>words</em> ("a dozen", "a couple", "several",
 *       "two", ...). No quantity signal at all means the line produces nothing; this is also what
 *       naturally screens out non-item lines with no digits (greetings, instructions, sign-offs).
 *   <li>Classify strictness from trigger phrases: "any brand"/"any kind"/"generic"/etc. →
 *       {@link RequirementStrictness#GENERIC}; "exactly"/"must be" → {@link
 *       RequirementStrictness#EXACT}; otherwise {@link RequirementStrictness#EQUIVALENT_ALLOWED}.
 *   <li>Pull a brand two ways: an explicit {@code brand: X} label, or — failing that — a
 *       capitalized, non-sentence-initial word left over after every quantity/filler/strictness
 *       phrase and parenthetical is stripped (e.g. "Elmer's" in "4 Elmer's glue sticks"). No
 *       candidate found means {@code brand = null} — never guessed.
 *   <li>Whatever words remain become the item name.
 *   <li>Confidence starts at 0.9 and is reduced for each ambiguity signal actually observed (no
 *       explicit quantity, more than one number in the line, more than one brand-like candidate,
 *       an unusually short or long line), floored at 0.4 — this stub never claims 1.0 certainty.
 * </ol>
 *
 * <h2>Honest limitations</h2>
 * This is regex-and-dictionary matching, not language understanding. It will misparse plenty of
 * real-world phrasing a real LLM would handle correctly: multi-item lines ("2 pencils and a
 * notebook"), quantities expressed as ranges ("10-12 markers"), item names that themselves contain
 * digits or capitalized words with no brand meaning ("3-ring binder", "1 pack of Post-it-style
 * notes" might misfire on "Post-it" as brand when that's arguably the item itself), non-English
 * text, or supply lists formatted as prose paragraphs rather than one-item-per-line. It is a
 * deliberately simple, fully testable stand-in behind the same {@link AiExtractionGateway}
 * interface a real Claude API call could later replace — see the interface's own Javadoc.
 */
@Component
public class StubAiExtractionGateway implements AiExtractionGateway {

    private static final double BASE_CONFIDENCE = 0.9;
    private static final double MIN_CONFIDENCE = 0.4;
    private static final double MAX_CONFIDENCE = 0.97;

    private static final Pattern LEADING_BULLET = Pattern.compile("^[\\-*•–—]+\\s*");
    private static final Pattern GREETING_OR_SIGNOFF = Pattern.compile(
            "(?i)^(hi|hello|hey|dear)\\b|^(thanks|thank you|regards|sincerely|best|best regards|warmly|cheers)\\b");

    private static final Pattern DIGIT = Pattern.compile("\\d{1,4}");
    private static final Pattern X_PREFIXED_DIGIT = Pattern.compile("(?i)\\bx\\s?(\\d{1,4})\\b");

    /** Longest phrases first so e.g. "a dozen" is tried before bare "dozen". */
    private static final Map<String, Integer> WORD_NUMBERS = new LinkedHashMap<>();

    static {
        WORD_NUMBERS.put("a couple of", 2);
        WORD_NUMBERS.put("a couple", 2);
        WORD_NUMBERS.put("couple of", 2);
        WORD_NUMBERS.put("couple", 2);
        WORD_NUMBERS.put("a dozen", 12);
        WORD_NUMBERS.put("dozen", 12);
        WORD_NUMBERS.put("a few", 3);
        WORD_NUMBERS.put("few", 3);
        WORD_NUMBERS.put("several", 4);
        WORD_NUMBERS.put("a single", 1);
        WORD_NUMBERS.put("one", 1);
        WORD_NUMBERS.put("two", 2);
        WORD_NUMBERS.put("three", 3);
        WORD_NUMBERS.put("four", 4);
        WORD_NUMBERS.put("five", 5);
        WORD_NUMBERS.put("six", 6);
        WORD_NUMBERS.put("seven", 7);
        WORD_NUMBERS.put("eight", 8);
        WORD_NUMBERS.put("nine", 9);
        WORD_NUMBERS.put("ten", 10);
        WORD_NUMBERS.put("eleven", 11);
        WORD_NUMBERS.put("twelve", 12);
    }

    private static final Pattern GENERIC_TRIGGER = Pattern.compile(
            "(?i)any brand|any kind|no brand preference|off[- ]brand|store brand|generic");
    private static final Pattern EXACT_TRIGGER = Pattern.compile("(?i)must be exactly|must be|exactly");

    private static final Pattern BRAND_LABEL = Pattern.compile(
            "(?i)brand\\s*:\\s*([A-Za-z0-9'&.\\-]+(?:\\s+[A-Za-z0-9'&.\\-]+){0,2})");

    /** Removed (globally, case-insensitively) before the item name and brand heuristic are read
     *  off whatever text remains — every one of these is a signal already captured elsewhere
     *  (quantity, strictness) or pure noise, never part of an item's real name. */
    private static final List<Pattern> FILLER_PATTERNS = List.of(
            Pattern.compile("(?i)\\bper\\s+student\\b"),
            Pattern.compile("(?i)\\bper\\s+child\\b"),
            Pattern.compile("(?i)\\bper\\s+kid\\b"),
            Pattern.compile("(?i)\\bany\\s+brand\\b"),
            Pattern.compile("(?i)\\bany\\s+kind(\\s+is\\s+fine)?\\b"),
            Pattern.compile("(?i)\\bno\\s+brand\\s+preference\\b"),
            Pattern.compile("(?i)\\bstore\\s+brand\\b"),
            Pattern.compile("(?i)\\boff-brand\\b"),
            Pattern.compile("(?i)\\bgeneric\\b"),
            Pattern.compile("(?i)\\bmust\\s+be\\s+exactly\\b"),
            Pattern.compile("(?i)\\bmust\\s+be\\b"),
            Pattern.compile("(?i)\\bexactly\\b"),
            Pattern.compile("(?i)\\beach\\b"),
            Pattern.compile("(?i)\\bneed(ed)?\\b"),
            Pattern.compile("(?i)\\babout\\b"),
            Pattern.compile("(?i)\\bis\\s+fine\\b"),
            Pattern.compile("(?i)\\bpreferred\\b"),
            Pattern.compile("(?i)\\bthe\\b"),
            Pattern.compile("(?i)\\bbrand\\s*:\\s*"),
            Pattern.compile("(?i)\\bbrand\\b")
    );

    private static final Pattern CONTAINER_OF = Pattern.compile(
            "(?i)\\b(boxes?|packs?|bottles?|pairs?|sets?|tubes?|reams?|packages?)\\s+of\\b");
    private static final Pattern PARENTHETICAL = Pattern.compile("\\([^)]*\\)");
    private static final Pattern LEADING_TRAILING_PUNCTUATION = Pattern.compile("^[\\s\\-,;:.]+|[\\s\\-,;:.]+$");

    private static final Pattern CAPITALIZED_WORD = Pattern.compile("^[A-Z][a-zA-Z]*('s)?$");

    @Override
    public List<ExtractedRequirement> extract(String rawText) {
        List<ExtractedRequirement> results = new ArrayList<>();
        if (rawText == null || rawText.isBlank()) {
            return results;
        }
        for (String rawLine : rawText.split("\\R")) {
            parseLine(rawLine).ifPresent(results::add);
        }
        return results;
    }

    private java.util.Optional<ExtractedRequirement> parseLine(String rawLine) {
        String stripped = LEADING_BULLET.matcher(rawLine.strip()).replaceFirst("").strip();
        if (stripped.length() < 3 || GREETING_OR_SIGNOFF.matcher(stripped).find()) {
            return java.util.Optional.empty();
        }

        QuantityMatch quantity = findQuantity(stripped);
        if (quantity == null) {
            // No plausible quantity at all — never fabricate a requirement to fill the gap.
            return java.util.Optional.empty();
        }

        RequirementStrictness strictness = classifyStrictness(stripped);

        String workLine = stripped;
        Brand brand = extractExplicitBrandLabel(workLine);
        if (brand != null) {
            workLine = brand.remainingText();
        }
        workLine = removeFirstOccurrence(workLine, quantity.matchedText());
        for (Pattern filler : FILLER_PATTERNS) {
            workLine = filler.matcher(workLine).replaceAll(" ");
        }
        workLine = CONTAINER_OF.matcher(workLine).replaceAll(" ");
        workLine = PARENTHETICAL.matcher(workLine).replaceAll(" ");
        workLine = workLine.replaceAll("\\s-\\s", " ").replaceAll("[,:;]+", " ").replaceAll("\\s+", " ");
        workLine = LEADING_TRAILING_PUNCTUATION.matcher(workLine).replaceAll("").strip();

        // A capitalized word only reads as a plausible brand if it *isn't* the line's own opening
        // word (sentence-initial capitalization, e.g. "Pencils" in "Pencils x12 (any brand)") —
        // compared against the original line's first word, not workLine's, since quantity removal
        // can shift a genuine brand (e.g. "Elmer's" in "4 Elmer's glue sticks") into position 0.
        String firstWordOfOriginalLine = stripped.split("\\s+")[0];
        List<String> tokens = new ArrayList<>(List.of(workLine.isBlank() ? new String[0] : workLine.split("\\s+")));
        int brandCandidates = 0;
        String brandName = brand == null ? null : brand.name();
        for (int i = 0; i < tokens.size(); i++) {
            String candidate = tokens.get(i).replaceAll("[^A-Za-z']", "");
            if (candidate.equals(firstWordOfOriginalLine) || !CAPITALIZED_WORD.matcher(candidate).matches()) {
                continue;
            }
            brandCandidates++;
            if (brandName == null) {
                brandName = candidate;
                tokens.remove(i);
                i--;
            }
        }

        String name = String.join(" ", tokens).strip();
        if (name.isBlank()) {
            // Nothing left that reads as an item name — don't invent one.
            return java.util.Optional.empty();
        }

        double confidence = computeConfidence(quantity, brandCandidates, stripped);
        return java.util.Optional.of(new ExtractedRequirement(
                name, quantity.value(), brandName, strictness, stripped, confidence));
    }

    private QuantityMatch findQuantity(String line) {
        Matcher xPrefixed = X_PREFIXED_DIGIT.matcher(line);
        List<MatchResult> digitMatches = DIGIT.matcher(line).results().toList();
        if (!digitMatches.isEmpty()) {
            MatchResult first = digitMatches.get(0);
            String matchedText = first.group();
            if (xPrefixed.find() && xPrefixed.start(1) == first.start()) {
                matchedText = xPrefixed.group();
            }
            return new QuantityMatch(Integer.parseInt(first.group()), matchedText, true, digitMatches.size() > 1);
        }
        for (Map.Entry<String, Integer> entry : WORD_NUMBERS.entrySet()) {
            Matcher m = Pattern.compile("(?i)\\b" + Pattern.quote(entry.getKey()) + "\\b").matcher(line);
            if (m.find()) {
                return new QuantityMatch(entry.getValue(), m.group(), false, false);
            }
        }
        return null;
    }

    private RequirementStrictness classifyStrictness(String line) {
        if (GENERIC_TRIGGER.matcher(line).find()) {
            return RequirementStrictness.GENERIC;
        }
        if (EXACT_TRIGGER.matcher(line).find()) {
            return RequirementStrictness.EXACT;
        }
        return RequirementStrictness.EQUIVALENT_ALLOWED;
    }

    private Brand extractExplicitBrandLabel(String line) {
        Matcher m = BRAND_LABEL.matcher(line);
        if (!m.find()) {
            return null;
        }
        String brandName = m.group(1).replaceAll("[),.;]+$", "").strip();
        String remaining = line.substring(0, m.start()) + " " + line.substring(m.end());
        return new Brand(brandName, remaining);
    }

    private String removeFirstOccurrence(String line, String matchedText) {
        int idx = line.indexOf(matchedText);
        if (idx < 0) {
            return line;
        }
        return line.substring(0, idx) + " " + line.substring(idx + matchedText.length());
    }

    private double computeConfidence(QuantityMatch quantity, int brandCandidates, String originalLine) {
        double confidence = BASE_CONFIDENCE;
        if (!quantity.explicit()) {
            confidence -= 0.25;
        }
        if (quantity.multipleNumbersPresent()) {
            confidence -= 0.15;
        }
        if (brandCandidates > 1) {
            confidence -= 0.1;
        }
        int wordCount = originalLine.strip().split("\\s+").length;
        if (wordCount <= 2 || wordCount >= 18) {
            confidence -= 0.15;
        }
        return Math.max(MIN_CONFIDENCE, Math.min(MAX_CONFIDENCE, confidence));
    }

    private record QuantityMatch(int value, String matchedText, boolean explicit, boolean multipleNumbersPresent) {
    }

    private record Brand(String name, String remainingText) {
    }
}
