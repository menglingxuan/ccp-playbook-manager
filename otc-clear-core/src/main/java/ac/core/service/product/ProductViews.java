package ac.core.service.product;

import java.util.List;

/** Read models used by the product definition screens. */
public final class ProductViews {

    private ProductViews() {
    }

    /** Platform with its associated base products. */
    public record PlatformView(String code, String name, String description, List<BaseProductSummary> baseProducts) {

        public PlatformView {
            baseProducts = List.copyOf(baseProducts);
        }
    }

    /** Base product summary. */
    public record BaseProductSummary(String id, String platformCode, String code, String name,
            String description, int sortOrder, long fieldCount, long productCount, String schemaHash) {
    }

    /** Base product field. */
    public record BaseFieldView(String id, String fieldKey, String header, String displayName,
            List<String> aliases, String valueTypeCode, String valueTypeLabel, String roleCode, String roleLabel,
            List<String> tags, boolean required, int sortOrder, String remark) {

        public BaseFieldView {
            aliases = List.copyOf(aliases);
            tags = List.copyOf(tags);
        }
    }

    /** Concrete product summary. */
    public record ProductSummary(String id, String platformCode, String baseProductCode, String code, String name,
            String description, String status, boolean strictMatch, int fieldCount, long caseCount,
            String schemaHash, boolean definitionValid) {
    }

    /**
     * Product field with inheritance information.
     *
     * @param sortOrder       order inside the product, maintained by the product itself
     * @param baseSortOrder   order of the owning base product field, shown as the parent number
     */
    public record ProductFieldView(String id, String baseFieldId, String fieldKey, String header,
            String displayName, List<String> aliases, String valueTypeCode, String valueTypeLabel,
            String roleCode, String roleLabel, List<String> tags, boolean required, boolean requiredFromBase,
            int sortOrder, int baseSortOrder, String remark) {

        public ProductFieldView {
            aliases = List.copyOf(aliases);
            tags = List.copyOf(tags);
        }
    }

    /** Base product detail. */
    public record BaseProductDetail(BaseProductSummary baseProduct, List<BaseFieldView> fields,
            List<ProductSummary> products) {

        public BaseProductDetail {
            fields = List.copyOf(fields);
            products = List.copyOf(products);
        }
    }

    /** Product detail. */
    public record ProductDetail(ProductSummary product, List<ProductFieldView> fields) {

        public ProductDetail {
            fields = List.copyOf(fields);
        }
    }
}
