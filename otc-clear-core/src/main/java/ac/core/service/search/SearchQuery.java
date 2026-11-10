package ac.core.service.search;

import java.util.Locale;
import java.util.Set;

/**
 * Parsed global search expression.
 *
 * <p>Free words search every scope, while {@code key:value} tokens narrow the target. Unknown keys
 * stay part of the free text, so values that contain a colon (dates, paths) keep working.
 *
 * <pre>
 *   smoke                       free text, all scopes
 *   product:IRS smoke           batches of IRS containing "smoke"
 *   tag:regression              batches tagged regression
 *   value:USD                   cases whose field values contain USD
 *   scope:case USD              only case hits
 * </pre>
 *
 * @param text      free text applied to the enabled scopes, {@code null} when only qualifiers were given
 * @param tag       exact batch tag
 * @param product   batch product code
 * @param batchId   restrict the case scope to one batch
 * @param caseBatch text matched against batch file, sheet, note and tags
 * @param caseText  text matched against case ids and stored field values
 * @param scope     requested result groups
 */
public record SearchQuery(String text, String tag, String product, String batchId, String caseBatch,
        String caseText, Scope scope) {

    /** Keys understood as qualifiers. */
    private static final Set<String> KEYS = Set.of("tag", "product", "batch", "case", "value", "field",
            "note", "file", "scope", "batchid");

    /** Result groups of one search. */
    public enum Scope {
        ALL,
        BATCH,
        CASE;

        static Scope of(String value) {
            if (value == null) {
                return ALL;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "batch", "batches" -> BATCH;
                case "case", "cases", "field", "fields", "value", "values" -> CASE;
                default -> ALL;
            };
        }
    }

    /** True when there is nothing to search for. */
    public boolean isEmpty() {
        return blank(text) && blank(tag) && blank(product) && blank(batchId) && blank(caseBatch)
                && blank(caseText);
    }

    /** Whether batch hits are wanted. */
    public boolean wantsBatches() {
        return scope != Scope.CASE;
    }

    /** Whether case hits are wanted. */
    public boolean wantsCases() {
        return scope != Scope.BATCH;
    }

    /** Text used for the batch scope: explicit {@code batch:}, {@code note:} or {@code file:} wins. */
    public String batchText() {
        return !blank(caseBatch) ? caseBatch : text;
    }

    /** Text used for the case scope: explicit {@code case:} or {@code value:} wins. */
    public String caseSearchText() {
        return !blank(caseText) ? caseText : text;
    }

    /**
     * Parses a raw search expression.
     *
     * @param raw user input
     * @return parsed expression, never {@code null}
     */
    public static SearchQuery parse(String raw) {
        String tag = null;
        String product = null;
        String batchId = null;
        String batchPart = null;
        String casePart = null;
        Scope scope = Scope.ALL;
        StringBuilder free = new StringBuilder();
        String[] tokens = (raw == null ? "" : raw).trim().split("\\s+");
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            int colon = token.indexOf(':');
            String key = colon > 0 ? token.substring(0, colon).toLowerCase(Locale.ROOT) : "";
            if (colon > 0 && KEYS.contains(key)) {
                String value = token.substring(colon + 1);
                switch (key) {
                    case "tag" -> tag = value;
                    case "product" -> product = value;
                    case "batchid" -> batchId = value;
                    case "batch", "note", "file" -> batchPart = join(batchPart, value);
                    case "case", "value", "field" -> casePart = join(casePart, value);
                    case "scope" -> scope = Scope.of(value);
                    default -> free.append(' ').append(token);
                }
                continue;
            }
            free.append(' ').append(token);
        }
        String freeText = free.toString().trim();
        return new SearchQuery(freeText.isEmpty() ? null : freeText, emptyToNull(tag),
                emptyToNull(product), emptyToNull(batchId), batchPart, casePart, scope);
    }

    private static String join(String current, String value) {
        if (value == null || value.isBlank()) {
            return current;
        }
        return current == null ? value.trim() : current + " " + value.trim();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
