package ac.domain.product;

import ac.domain.ids.Hashing;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds {@link ProductSchema} instances from annotated product classes and validates the
 * definition: keys and orders must be unique, while headers may repeat on purpose.
 */
public final class ProductSchemaFactory {

    private ProductSchemaFactory() {
    }

    public static ProductSchema create(Class<?> productClass) {
        ProductDefinition definition = productClass.getAnnotation(ProductDefinition.class);
        if (definition == null) {
            throw new IllegalStateException(productClass.getName() + " is missing @ProductDefinition");
        }

        ProductField[] declared = productClass.getAnnotationsByType(ProductField.class);
        if (declared.length == 0) {
            throw new IllegalStateException(productClass.getName() + " declares no @ProductField");
        }

        List<ProductField> fields = new ArrayList<>(List.of(declared));
        Set<String> keys = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (ProductField field : declared) {
            if (!keys.add(field.key())) {
                throw new IllegalStateException("Duplicate field key '" + field.key() + "' in " + definition.code());
            }
            if (!orders.add(field.order())) {
                throw new IllegalStateException("Duplicate field order " + field.order() + " in " + definition.code());
            }
            if (field.header() == null || field.header().isBlank()) {
                throw new IllegalStateException("Field '" + field.key() + "' in " + definition.code() + " has no header");
            }
        }

        fields.sort((left, right) -> Integer.compare(left.order(), right.order()));

        Map<String, Integer> headerCounts = new HashMap<>();
        List<FieldDef> resolved = new ArrayList<>(fields.size());
        FieldDef uniqueField = null;
        for (ProductField field : fields) {
            String normalizedHeader = HeaderNormalizer.normalize(field.header());
            int occurrence = headerCounts.merge(normalizedHeader, 1, Integer::sum);

            Set<String> normalizedNames = new HashSet<>();
            normalizedNames.add(normalizedHeader);
            for (String alias : field.aliases()) {
                String normalizedAlias = HeaderNormalizer.normalize(alias);
                if (!normalizedAlias.isEmpty()) {
                    normalizedNames.add(normalizedAlias);
                }
            }

            if (field.role() == FieldRole.KEY) {
                if (uniqueField != null) {
                    throw new IllegalStateException(
                            "Product " + definition.code() + " declares more than one KEY field");
                }
                if (field.type() != FieldType.TEXT) {
                    throw new IllegalStateException(
                            "KEY field '" + field.key() + "' must be TEXT in " + definition.code());
                }
            }

            FieldDef def = new FieldDef(
                    field.key(),
                    field.order(),
                    field.header(),
                    List.of(field.aliases()),
                    field.type(),
                    field.role(),
                    field.required(),
                    field.businessKey(),
                    field.format(),
                    field.clonePattern(),
                    occurrence,
                    normalizedNames);
            resolved.add(def);
            if (def.isKey()) {
                uniqueField = def;
            }
        }

        Map<String, FieldDef> byKey = new LinkedHashMap<>();
        for (FieldDef def : resolved) {
            byKey.put(def.key(), def);
        }

        String schemaHash = Hashing.sha256Hex(canonicalForm(definition, resolved));
        return new ProductSchema(definition.code(), definition.name(), definition.description(),
                resolved, byKey, uniqueField, schemaHash);
    }

    private static String canonicalForm(ProductDefinition definition, List<FieldDef> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append(definition.code()).append('|').append(definition.name()).append('\n');
        for (FieldDef field : fields) {
            sb.append(field.key()).append('|')
                    .append(field.order()).append('|')
                    .append(field.header()).append('|')
                    .append(String.join(",", field.aliases())).append('|')
                    .append(field.type()).append('|')
                    .append(field.role()).append('|')
                    .append(field.required()).append('|')
                    .append(field.businessKey()).append('|')
                    .append(field.format()).append('|')
                    .append(field.clonePattern()).append('\n');
        }
        return sb.toString();
    }
}
