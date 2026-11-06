package ac.domain.product;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolved product schema: ordered fields plus lookup helpers.
 *
 * @param code       product code, for example {@code IRS}
 * @param name       display name
 * @param description free form description shown on the products page
 * @param fields     fields ordered by {@link FieldDef#order()}
 * @param byKey      field lookup by internal key
 * @param uniqueField field flagged as {@link FieldRole#KEY}, may be {@code null}
 * @param schemaHash deterministic fingerprint of the definition
 */
public record ProductSchema(
        String code,
        String name,
        String description,
        List<FieldDef> fields,
        Map<String, FieldDef> byKey,
        FieldDef uniqueField,
        String schemaHash) {

    public ProductSchema {
        fields = List.copyOf(fields);
        byKey = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(byKey));
    }

    public Optional<FieldDef> field(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    public FieldDef requireField(String key) {
        FieldDef def = byKey.get(key);
        if (def == null) {
            throw new IllegalArgumentException("Unknown field '" + key + "' for product " + code);
        }
        return def;
    }

    public List<FieldDef> businessFields() {
        return fields.stream().filter(FieldDef::businessKey).toList();
    }

    public List<FieldDef> requiredFields() {
        return fields.stream().filter(FieldDef::required).toList();
    }

    public boolean hasDuplicateHeaders() {
        return fields.stream().anyMatch(FieldDef::hasDuplicateHeader);
    }

    public List<String> headers() {
        return fields.stream().map(FieldDef::header).toList();
    }
}
