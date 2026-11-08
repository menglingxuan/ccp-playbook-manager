package ac.domain.product;

import java.util.List;

/**
 * Immutable field definition resolved from a persisted product field row.
 *
 * @param key              stable internal identifier
 * @param order            1-based schema order
 * @param header           header text written on export
 * @param aliases          alternative header spellings, shown in the UI and never used for imports
 * @param type             logical type
 * @param role             business or managed role
 * @param required         whether the value must be present
 * @param businessKey      whether the field contributes to the business record id
 * @param keyField         whether the field is the single business key (trade id) of the product
 * @param format           optional display/parse hint
 * @param clonePattern     naming template for the clone feature
 * @param headerOccurrence 1-based occurrence index among fields sharing the same column name
 * @param tags             free form labels declared by the field meta information
 * @param enumValues       allowed values when {@code type} is {@link FieldType#ENUM}
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
        boolean keyField,
        String format,
        String clonePattern,
        int headerOccurrence,
        List<String> tags,
        List<String> enumValues) {

    public FieldDef {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        tags = tags == null ? List.of() : List.copyOf(tags);
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
    }

    /** Convenience factory for definitions that carry no tags or enumerated values. */
    public static FieldDef of(String key, int order, String header, List<String> aliases, FieldType type,
            FieldRole role, boolean required, boolean businessKey, String format, String clonePattern,
            int headerOccurrence) {
        return new FieldDef(key, order, header, aliases, type, role, required, businessKey, false, format,
                clonePattern, headerOccurrence, List.of(), List.of());
    }

    public boolean isKey() {
        return keyField;
    }

    public boolean isManaged() {
        return role == FieldRole.MANAGED;
    }

    public boolean hasDuplicateHeader() {
        return headerOccurrence > 1;
    }

    public boolean isEnumerated() {
        return type == FieldType.ENUM && !enumValues.isEmpty();
    }

    /** Header text used by the UI; duplicate headers get a {@code (#n)} suffix. */
    public String displayHeader() {
        return hasDuplicateHeader() ? header + " (#" + headerOccurrence + ")" : header;
    }
}
