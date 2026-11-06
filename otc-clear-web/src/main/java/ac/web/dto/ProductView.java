package ac.web.dto;

import ac.domain.product.FieldDef;
import ac.domain.product.ProductSchema;
import java.util.List;

/**
 * Product definition view.
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

    /** One field definition. */
    public record FieldView(
            String key,
            int order,
            String header,
            String displayHeader,
            String type,
            String role,
            boolean required,
            boolean businessKey,
            boolean duplicate,
            String aliases,
            String clonePattern,
            String format) {

        public static FieldView of(FieldDef field) {
            return new FieldView(field.key(), field.order(), field.header(), field.displayHeader(),
                    field.type().name(), field.role().name(), field.required(), field.businessKey(),
                    field.hasDuplicateHeader(), String.join(", ", field.aliases()), field.clonePattern(),
                    field.format());
        }
    }

    public static ProductView of(ProductSchema schema) {
        return new ProductView(schema.code(), schema.name(), schema.description(), schema.schemaHash(),
                schema.hasDuplicateHeaders(), schema.fields().stream().map(FieldView::of).toList());
    }
}
