package ac.domain.product;

import ac.domain.ids.Hashing;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolved product schema: ordered fields plus lookup helpers.
 *
 * @param code        product code, unique within the tenant
 * @param name        display name
 * @param description free form description shown in the UI
 * @param strictMatch when true, imports must match the business field order exactly
 * @param fields      fields ordered by {@link FieldDef#order()}
 * @param byKey       field lookup by internal key
 * @param uniqueField field flagged as the business key, may be {@code null}
 * @param schemaHash  deterministic fingerprint of the definition
 */
public record ProductSchema(
        String code,
        String name,
        String description,
        boolean strictMatch,
        List<FieldDef> fields,
        Map<String, FieldDef> byKey,
        FieldDef uniqueField,
        String schemaHash) {

    public ProductSchema {
        fields = List.copyOf(fields);
        byKey = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(byKey));
    }

    /**
     * Builds a schema from unordered field definitions: sorts by order, resolves duplicate header
     * occurrences, validates keys and computes the schema hash.
     *
     * @throws IllegalStateException when keys or the business key field are invalid
     */
    public static ProductSchema assemble(String code, String name, String description,
            List<FieldDef> rawFields) {
        return assemble(code, name, description, false, rawFields);
    }

    /**
     * Builds a schema, including the strict matching flag used by imports.
     *
     * @throws IllegalStateException when keys or the business key field are invalid
     */
    public static ProductSchema assemble(String code, String name, String description, boolean strictMatch,
            List<FieldDef> rawFields) {
        if (code == null || code.isBlank()) {
            throw new IllegalStateException("Product code is required");
        }
        List<FieldDef> sorted = new ArrayList<>(rawFields == null ? List.of() : rawFields);
        sorted.sort(Comparator.comparingInt(FieldDef::order));

        Set<String> keys = new LinkedHashSet<>();
        Map<String, Integer> headerCounts = new HashMap<>();
        List<FieldDef> resolved = new ArrayList<>(sorted.size());
        FieldDef uniqueField = null;

        for (FieldDef field : sorted) {
            if (!keys.add(field.key())) {
                throw new IllegalStateException("Duplicate field key '" + field.key() + "' in " + code);
            }
            String normalizedHeader = HeaderNormalizer.normalize(field.header());
            int occurrence = headerCounts.merge(normalizedHeader, 1, Integer::sum);

            Set<String> normalizedNames = new LinkedHashSet<>();
            normalizedNames.add(normalizedHeader);
            for (String alias : field.aliases()) {
                String normalizedAlias = HeaderNormalizer.normalize(alias);
                if (!normalizedAlias.isEmpty()) {
                    normalizedNames.add(normalizedAlias);
                }
            }

            FieldDef def = new FieldDef(field.key(), field.order(), field.header(), field.aliases(),
                    field.type(), field.role(), field.required(), field.businessKey(), field.keyField(),
                    field.format(), field.clonePattern(), occurrence, normalizedNames, field.tags(),
                    field.enumValues());

            if (def.isKey()) {
                if (uniqueField != null) {
                    throw new IllegalStateException("Product " + code + " declares more than one key field");
                }
                if (def.type() != FieldType.TEXT) {
                    throw new IllegalStateException("Key field '" + def.key() + "' must be TEXT in " + code);
                }
                uniqueField = def;
            }
            resolved.add(def);
        }

        Map<String, FieldDef> byKey = new LinkedHashMap<>();
        for (FieldDef def : resolved) {
            byKey.put(def.key(), def);
        }
        return new ProductSchema(code, name, description, strictMatch, resolved, byKey, uniqueField,
                Hashing.sha256Hex(canonicalForm(code, name, strictMatch, resolved)));
    }

    private static String canonicalForm(String code, String name, boolean strictMatch,
            List<FieldDef> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append(code).append('|').append(name).append('|').append(strictMatch).append('\n');
        for (FieldDef field : fields) {
            sb.append(field.key()).append('|')
                    .append(field.order()).append('|')
                    .append(field.header()).append('|')
                    .append(String.join(",", field.aliases())).append('|')
                    .append(field.type()).append('|')
                    .append(field.role()).append('|')
                    .append(field.required()).append('|')
                    .append(field.businessKey()).append('|')
                    .append(field.keyField()).append('|')
                    .append(field.format()).append('|')
                    .append(field.clonePattern()).append('|')
                    .append(String.join(",", field.tags())).append('|')
                    .append(String.join(",", field.enumValues())).append('\n');
        }
        return sb.toString();
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

    /** Fields that build the trade block; managed columns are excluded. */
    public List<FieldDef> tradeFields() {
        return fields.stream().filter(field -> field.role() != FieldRole.MANAGED).toList();
    }

    /** Managed columns declared by the product. */
    public List<FieldDef> managedFields() {
        return fields.stream().filter(field -> field.role() == FieldRole.MANAGED).toList();
    }

    /** Required trade fields; managed columns never block an import. */
    public List<FieldDef> requiredFields() {
        return fields.stream()
                .filter(field -> field.required() && field.role() != FieldRole.MANAGED)
                .toList();
    }

    public boolean hasDuplicateHeaders() {
        return fields.stream().anyMatch(FieldDef::hasDuplicateHeader);
    }

    public List<String> headers() {
        return fields.stream().map(FieldDef::header).toList();
    }

    public List<String> tradeHeaders() {
        return tradeFields().stream().map(FieldDef::header).toList();
    }
}
