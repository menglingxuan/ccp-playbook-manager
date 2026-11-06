package ac.domain.product;

import java.util.List;
import java.util.Set;

/**
 * Immutable field definition resolved from {@link ProductField}.
 *
 * @param key              stable internal identifier
 * @param order            1-based schema order
 * @param header           header text written on export
 * @param aliases          alternative header spellings
 * @param type             logical type
 * @param role             trade or unique key role
 * @param required         whether the value must be present
 * @param businessKey      whether the field contributes to the business record id
 * @param format           optional display/parse hint
 * @param clonePattern     naming template for the clone feature
 * @param headerOccurrence 1-based occurrence index among fields sharing the same normalized header
 * @param normalizedNames  normalized header plus aliases, used by the aligner
 */
public record FieldDef(
        String key,
        int order,
        String header,
        List<String> aliases,
        FieldType type,
        FieldRole role,
        boolean required,
        boolean businessKey,
        String format,
        String clonePattern,
        int headerOccurrence,
        Set<String> normalizedNames) {

    public FieldDef {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        normalizedNames = normalizedNames == null ? Set.of() : Set.copyOf(normalizedNames);
    }

    public boolean isKey() {
        return role == FieldRole.KEY;
    }

    public boolean hasDuplicateHeader() {
        return headerOccurrence > 1;
    }

    /** Header text used by the UI; duplicate headers get a {@code (#n)} suffix. */
    public String displayHeader() {
        return hasDuplicateHeader() ? header + " (#" + headerOccurrence + ")" : header;
    }
}
