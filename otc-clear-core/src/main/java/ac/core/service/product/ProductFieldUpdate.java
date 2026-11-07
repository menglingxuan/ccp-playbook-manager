package ac.core.service.product;

import java.util.List;

/**
 * Product level field overrides.
 *
 * @param required  required flag; inherited required fields cannot be relaxed
 * @param sortOrder display order within the product
 * @param tags      tags shown for this product
 * @param remark    product specific note
 */
public record ProductFieldUpdate(boolean required, int sortOrder, List<String> tags, String remark) {

    public ProductFieldUpdate {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
