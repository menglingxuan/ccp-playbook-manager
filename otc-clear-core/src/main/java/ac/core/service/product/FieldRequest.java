package ac.core.service.product;

import java.util.List;

/**
 * Field definition request shared by base product and product editing.
 *
 * @param fieldKey      internal key; generated from the header when blank
 * @param header        column header
 * @param displayName   internal display name (not shown in the list)
 * @param aliases       alternative headers
 * @param valueTypeCode VALUE_TYPE meta item code
 * @param roleCode      FIELD_ROLE meta item code
 * @param tags          FIELD_TAG meta item codes
 * @param required      whether the value must be present
 * @param remark        free form note
 * @param sortOrder     1-based order; appended when {@code null}
 * @param validationExpr rule codes combined with {@code AND}, {@code OR}, {@code NOT} and parentheses;
 *                       blank clears the binding
 */
public record FieldRequest(
        String fieldKey,
        String header,
        String displayName,
        List<String> aliases,
        String valueTypeCode,
        String roleCode,
        List<String> tags,
        boolean required,
        String remark,
        Integer sortOrder,
        String validationExpr) {

    public FieldRequest {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
