package ac.domain.casefile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Formula text helpers.
 *
 * <p>Formula text is always stored verbatim, because export rebuilds the workbook from it and a
 * masked reference such as {@code =D#*E#} cannot be recalculated. Normalization only happens right
 * before the business record id is hashed.
 */
public final class Formulas {

    private static final Pattern RELATIVE_ROW_REFERENCE = Pattern.compile("(\\$?[A-Za-z]{1,3})\\$?\\d+");

    private Formulas() {
    }

    /** Masks row numbers inside cell references so the same relative formula matches across rows. */
    public static String normalize(String formula) {
        if (formula == null) {
            return null;
        }
        return RELATIVE_ROW_REFERENCE.matcher(formula).replaceAll("$1#");
    }

    /** Normalized copy of a formula map, used for hashing only. */
    public static Map<String, String> normalized(Map<String, String> formulas) {
        if (formulas == null || formulas.isEmpty()) {
            return formulas == null ? Map.of() : formulas;
        }
        Map<String, String> result = new LinkedHashMap<>();
        formulas.forEach((key, value) -> result.put(key, normalize(value)));
        return result;
    }
}
