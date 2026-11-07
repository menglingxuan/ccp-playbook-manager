package ac.web.api;

import ac.core.service.product.FieldRequest;
import ac.core.service.product.ProductDefinitionService;
import ac.core.service.product.ProductFieldUpdate;
import ac.core.service.product.ProductSearchService;
import ac.core.service.product.ProductViews.BaseFieldView;
import ac.core.service.product.ProductViews.BaseProductDetail;
import ac.core.service.product.ProductViews.BaseProductSummary;
import ac.core.service.product.ProductViews.PlatformView;
import ac.core.service.product.ProductViews.ProductDetail;
import ac.core.service.product.ProductViews.ProductFieldView;
import ac.core.service.product.ProductViews.ProductSummary;
import ac.web.config.TenantProvider;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Product definition endpoints: platform to base product to concrete product.
 *
 * <p>These endpoints edit the definition tables; {@code /api/products} exposes the resolved schemas.
 */
@RestController
@RequestMapping("/api/catalog")
public class ProductDefinitionController {

    private final ProductDefinitionService definitions;
    private final ProductSearchService search;
    private final TenantProvider tenantProvider;

    public ProductDefinitionController(ProductDefinitionService definitions, ProductSearchService search,
            TenantProvider tenantProvider) {
        this.definitions = definitions;
        this.search = search;
        this.tenantProvider = tenantProvider;
    }

    // ---------------------------------------------------------------- catalogue

    @GetMapping
    public List<PlatformView> platforms() {
        return definitions.platforms(tenantId());
    }

    /** Global search, optionally limited to one platform, across base products, products and fields. */
    @GetMapping("/search")
    public ProductSearchService.Result searchCatalog(@RequestParam("q") String query,
            @RequestParam(value = "platform", required = false) String platform) {
        return search.search(tenantId(), platform, query);
    }

    @GetMapping("/base-codes/suggestions")
    public List<String> suggestedBaseCodes() {
        return definitions.suggestedBaseCodes();
    }

    @PostMapping("/platforms/{platform}/base-products")
    public BaseProductSummary createBaseProduct(@PathVariable String platform,
            @RequestBody BaseProductRequest request) {
        return definitions.createBaseProduct(tenantId(), platform, request.code(), request.name(),
                request.description());
    }

    // ------------------------------------------------------------- base products

    @GetMapping("/base-products/{id}")
    public BaseProductDetail baseProduct(@PathVariable String id) {
        return definitions.baseProductDetail(tenantId(), id);
    }

    @PatchMapping("/base-products/{id}")
    public BaseProductDetail updateBaseProduct(@PathVariable String id,
            @RequestBody BaseProductUpdate request) {
        definitions.updateBaseProduct(tenantId(), id, request.name(), request.description(),
                request.sortOrder());
        return definitions.baseProductDetail(tenantId(), id);
    }

    @DeleteMapping("/base-products/{id}")
    public Map<String, Object> deleteBaseProduct(@PathVariable String id) {
        definitions.deleteBaseProduct(tenantId(), id);
        return Map.of("deleted", true);
    }

    @PostMapping("/base-products/{id}/fields")
    public BaseFieldView addField(@PathVariable String id, @RequestBody FieldRequest request) {
        return definitions.addBaseField(tenantId(), id, request);
    }

    /** Creates one business field per file header; returns the created field keys. */
    @PostMapping("/base-products/{id}/fields/from-headers")
    public Map<String, Object> importFieldsFromHeaders(@PathVariable String id,
            @RequestBody HeaderImportRequest request) {
        List<String> created = definitions.importBaseFieldsFromHeaders(tenantId(), id, request.headers());
        return Map.of("created", created);
    }

    @PatchMapping("/base-products/{id}/fields/{fieldId}")
    public BaseFieldView updateField(@PathVariable String id, @PathVariable String fieldId,
            @RequestBody FieldRequest request) {
        return definitions.updateBaseField(tenantId(), id, fieldId, request);
    }

    @DeleteMapping("/base-products/{id}/fields/{fieldId}")
    public Map<String, Object> deleteField(@PathVariable String id, @PathVariable String fieldId) {
        return Map.of("removedProductFields",
                definitions.deleteBaseField(tenantId(), id, fieldId));
    }

    @PostMapping("/base-products/{id}/fields/reorder")
    public BaseProductDetail reorderFields(@PathVariable String id,
            @RequestBody ReorderRequest request) {
        definitions.reorderBaseFields(tenantId(), id, request.fieldIds());
        return definitions.baseProductDetail(tenantId(), id);
    }

    @PostMapping("/base-products/{id}/products")
    public ProductSummary createProduct(@PathVariable String id, @RequestBody ProductCreateRequest request) {
        return definitions.createProduct(tenantId(), id, request.code(), request.name(),
                request.description(), request.excludedFieldKeys());
    }

    // ------------------------------------------------------------------ products

    @GetMapping("/products/{id}")
    public ProductDetail product(@PathVariable String id) {
        return definitions.productDetail(tenantId(), id);
    }

    @PatchMapping("/products/{id}")
    public ProductDetail updateProduct(@PathVariable String id, @RequestBody ProductUpdate request) {
        definitions.updateProduct(tenantId(), id, request.name(), request.description(), request.strictMatch());
        return definitions.productDetail(tenantId(), id);
    }

    @DeleteMapping("/products/{id}")
    public Map<String, Object> deleteProduct(@PathVariable String id) {
        definitions.deleteProduct(tenantId(), id);
        return Map.of("deleted", true);
    }

    @PatchMapping("/products/{id}/fields/{fieldId}")
    public ProductFieldView updateProductField(@PathVariable String id, @PathVariable String fieldId,
            @RequestBody ProductFieldUpdate request) {
        return definitions.updateProductField(tenantId(), id, fieldId, request);
    }

    @DeleteMapping("/products/{id}/fields/{fieldId}")
    public Map<String, Object> deleteProductField(@PathVariable String id, @PathVariable String fieldId) {
        definitions.deleteProductField(tenantId(), id, fieldId);
        return Map.of("deleted", true);
    }

    @PostMapping("/products/{id}/fields/reorder")
    public ProductDetail reorderProductFields(@PathVariable String id, @RequestBody ReorderRequest request) {
        return definitions.reorderProductFields(tenantId(), id, request.fieldIds());
    }

    @PostMapping("/products/{id}/sync")
    public ProductDetail syncProduct(@PathVariable String id,
            @RequestBody(required = false) SyncRequest request) {
        boolean addMissing = request != null && request.addMissing();
        return definitions.syncProduct(tenantId(), id, addMissing);
    }

    private String tenantId() {
        return tenantProvider.tenantId();
    }

    /**
     * Header import payload.
     *
     * @param headers column headers read from the first row of a CSV file
     */
    public record HeaderImportRequest(List<String> headers) {

        public HeaderImportRequest {
            headers = headers == null ? List.of() : List.copyOf(headers);
        }
    }

    /**
     * New base product payload.
     *
     * @param code        free form code, unique per platform
     * @param name        display name; defaults to the known label or the code
     * @param description free form note
     */
    public record BaseProductRequest(String code, String name, String description) {
    }

    /**
     * Base product update payload.
     *
     * @param name        display name
     * @param description free form note
     * @param sortOrder   display order within the platform
     */
    public record BaseProductUpdate(String name, String description, Integer sortOrder) {
    }

    /**
     * New product payload.
     *
     * @param code               globally unique product code
     * @param name               display name
     * @param description        free form note
     * @param excludedFieldKeys  base field keys skipped while copying
     */
    public record ProductCreateRequest(String code, String name, String description,
            List<String> excludedFieldKeys) {

        public ProductCreateRequest {
            excludedFieldKeys = excludedFieldKeys == null ? List.of() : List.copyOf(excludedFieldKeys);
        }
    }

    /**
     * Product update payload.
     *
     * @param name        display name; unchanged when blank
     * @param description free form note; unchanged when {@code null}
     * @param strictMatch strict field order matching on import; unchanged when {@code null}
     */
    public record ProductUpdate(String name, String description, Boolean strictMatch) {
    }

    /**
     * Field reorder payload.
     *
     * @param fieldIds field ids in the new order
     */
    public record ReorderRequest(List<String> fieldIds) {

        public ReorderRequest {
            fieldIds = fieldIds == null ? List.of() : List.copyOf(fieldIds);
        }
    }

    /**
     * Sync payload.
     *
     * @param addMissing true to append base product fields missing from the product
     */
    public record SyncRequest(boolean addMissing) {
    }
}
