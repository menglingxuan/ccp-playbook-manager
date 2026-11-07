package ac.core.service.product;

import ac.core.persistence.MetaRepository;
import ac.domain.product.FieldType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Resolves the field meta information into the values used by field definitions.
 *
 * <p>Value types are fully meta driven: an item either names a built-in parser kind
 * ({@code TEXT}, {@code INTEGER}, {@code DECIMAL}, {@code DATE}, {@code BOOLEAN}) or references
 * another meta set, turning the field into an enumerated value.
 */
@Component
public class MetaLookup {

    public static final String VALUE_TYPE_SET = "VALUE_TYPE";
    public static final String FIELD_ROLE_SET = "FIELD_ROLE";
    public static final String FIELD_TAG_SET = "FIELD_TAG";

    private final MetaRepository meta;

    public MetaLookup(MetaRepository meta) {
        this.meta = meta;
    }

    /**
     * Resolved value type.
     *
     * @param code       meta item code
     * @param label      display label
     * @param fieldType  parser kind used at import time
     * @param enumValues allowed values for {@link FieldType#ENUM}, empty otherwise
     */
    public record ValueTypeSpec(String code, String label, FieldType fieldType, List<String> enumValues) {

        public ValueTypeSpec {
            enumValues = List.copyOf(enumValues);
        }
    }

    /**
     * Snapshot of the meta information needed to map product rows.
     *
     * @param valueTypes value type code to specification
     * @param roleLabels role code to label
     * @param tagLabels  tag code to label
     */
    public record Lookup(Map<String, ValueTypeSpec> valueTypes, Map<String, String> roleLabels,
            Map<String, String> tagLabels) {

        public ValueTypeSpec valueType(String code) {
            ValueTypeSpec spec = valueTypes.get(code);
            return spec == null ? new ValueTypeSpec(code, code, FieldType.TEXT, List.of()) : spec;
        }

        public boolean hasValueType(String code) {
            return valueTypes.containsKey(code);
        }
    }

    public Lookup load(String tenantId) {
        List<MetaRepository.MetaItemRow> allItems = meta.allItems(tenantId);
        Map<String, List<MetaRepository.MetaItemRow>> bySet = new LinkedHashMap<>();
        for (MetaRepository.MetaItemRow item : allItems) {
            bySet.computeIfAbsent(item.setCode(), key -> new java.util.ArrayList<>()).add(item);
        }

        Map<String, List<String>> enumValues = new LinkedHashMap<>();
        bySet.forEach((setCode, items) -> enumValues.put(setCode,
                items.stream().filter(MetaRepository.MetaItemRow::enabled)
                        .map(MetaRepository.MetaItemRow::label).toList()));

        Map<String, ValueTypeSpec> valueTypes = new LinkedHashMap<>();
        for (MetaRepository.MetaItemRow item : bySet.getOrDefault(VALUE_TYPE_SET, List.of())) {
            if (!item.enabled()) {
                continue;
            }
            if (item.refSetCode() != null && !item.refSetCode().isBlank()) {
                valueTypes.put(item.code(), new ValueTypeSpec(item.code(), item.label(), FieldType.ENUM,
                        enumValues.getOrDefault(item.refSetCode(), List.of())));
            } else {
                valueTypes.put(item.code(),
                        new ValueTypeSpec(item.code(), item.label(), baseType(item.code()), List.of()));
            }
        }

        Map<String, String> roleLabels = labels(bySet.getOrDefault(FIELD_ROLE_SET, List.of()));
        Map<String, String> tagLabels = labels(bySet.getOrDefault(FIELD_TAG_SET, List.of()));
        return new Lookup(valueTypes, roleLabels, tagLabels);
    }

    private static Map<String, String> labels(List<MetaRepository.MetaItemRow> items) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (MetaRepository.MetaItemRow item : items) {
            if (item.enabled()) {
                labels.put(item.code(), item.label());
            }
        }
        return labels;
    }

    /** Maps a built-in value type code to the parser kind; unknown codes fall back to text. */
    public static FieldType baseType(String code) {
        if (code == null) {
            return FieldType.TEXT;
        }
        return switch (code.trim().toUpperCase(Locale.ROOT)) {
            case "INTEGER" -> FieldType.INTEGER;
            case "DECIMAL" -> FieldType.DECIMAL;
            case "DATE" -> FieldType.DATE;
            case "BOOLEAN" -> FieldType.BOOLEAN;
            case "ENUM" -> FieldType.ENUM;
            default -> FieldType.TEXT;
        };
    }
}
