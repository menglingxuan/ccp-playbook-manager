package ac.web.dto;

import ac.domain.product.FieldDef;
import ac.domain.product.ProductSchema;
import java.util.List;

/**
 * Runtime product schema view used by import, export and the context banner.
 *
 * @param code              product code
 * @param name              display name
 * @param description       description
 * @param schemaHash        definition fingerprint
 * @param duplicateHeaders  true when the product declares duplicated headers
 * @param fields            ordered fields
 */
public record ProductView(
        String code,
        String name,
        String description,
        String schemaHash,
        boolean duplicateHeaders,
        List<FieldView> fields) {

    public ProductView {
        fields = List.copyOf(fields);
    }

    /**
     * One resolved field.
     *
     * @param key           internal key
     * @param order         1-based order
     * @param header        header text
     * @param type          logical value type
     * @param role          trade or key role
     * @param required      whether the value must be present
     * @param businessKey   whether the field contributes to the business id
     * @param duplicate     true when another field shares the header
     * @param tags          field tags
     */
    public record FieldView(
            String key,
            int order,
            String header,
            String type,
            String role,
            boolean required,
            boolean businessKey,
            boolean duplicate,
            List<String> tags) {

        public FieldView {
            tags = List.copyOf(tags);
        }

        public static FieldView of(FieldDef field) {
            return new FieldView(field.key(), field.order(), field.header(), field.type().name(),
                    field.role().name(), field.required(), field.businessKey(), field.hasDuplicateHeader(),
                    field.tags());
        }
    }

    public static ProductView of(ProductSchema schema) {
        return new ProductView(schema.code(), schema.name(), schema.description(), schema.schemaHash(),
                schema.hasDuplicateHeaders(), schema.fields().stream().map(FieldView::of).toList());
    }
}
