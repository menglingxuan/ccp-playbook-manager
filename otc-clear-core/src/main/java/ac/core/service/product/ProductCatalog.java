package ac.core.service.product;

import ac.core.common.ApiException;
import ac.core.persistence.BaseProductRepository;
import ac.core.persistence.ProductRepository;
import ac.domain.product.FieldDef;
import ac.domain.product.ProductSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Resolves the active product schemas used by import, export, clone and search.
 *
 * <p>Schemas are read from the product definition tables and cached until the definition changes.
 */
@Component
public class ProductCatalog {

    private final ProductRepository products;
    private final BaseProductRepository baseProducts;
    private final MetaLookup metaLookup;
    private final ProductSchemaMapper mapper;
    private final Map<String, ProductSchema> cache = new ConcurrentHashMap<>();

    public ProductCatalog(ProductRepository products, BaseProductRepository baseProducts,
            MetaLookup metaLookup, ProductSchemaMapper mapper) {
        this.products = products;
        this.baseProducts = baseProducts;
        this.metaLookup = metaLookup;
        this.mapper = mapper;
    }

    public List<ProductSchema> all(String tenantId) {
        MetaLookup.Lookup lookup = metaLookup.load(tenantId);
        List<ProductSchema> schemas = new ArrayList<>();
        for (ProductRepository.ProductRow row : products.listActive(tenantId)) {
            schemas.add(schemaOf(tenantId, row, lookup));
        }
        return List.copyOf(schemas);
    }

    public Optional<ProductSchema> find(String tenantId, String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String key = cacheKey(tenantId, code);
        ProductSchema cached = cache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        return products.findByCode(tenantId, code.trim().toUpperCase(Locale.ROOT))
                .map(row -> schemaOf(tenantId, row, metaLookup.load(tenantId)));
    }

    public ProductSchema require(String tenantId, String code) {
        if (code == null || code.isBlank()) {
            throw ApiException.invalid("Product code is required");
        }
        return find(tenantId, code).orElseThrow(
                () -> ApiException.notFound("Unknown product '" + code + "'"));
    }

    public FieldDef requireField(String tenantId, String productCode, String fieldKey) {
        return require(tenantId, productCode).requireField(fieldKey);
    }

    public void invalidate(String tenantId) {
        String prefix = tenantId + "|";
        cache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public void invalidate(String tenantId, String code) {
        if (code != null) {
            cache.remove(cacheKey(tenantId, code));
        }
    }

    /** Base product schema preview, used by the definition screens. */
    public ProductSchema baseSchema(String tenantId, String baseProductId) {
        BaseProductRepository.BaseProductRow base = baseProducts.findById(tenantId, baseProductId)
                .orElseThrow(() -> ApiException.notFound("Base product " + baseProductId + " does not exist"));
        return mapper.toBaseSchema(base, baseProducts.listFields(tenantId, baseProductId),
                metaLookup.load(tenantId));
    }

    private ProductSchema schemaOf(String tenantId, ProductRepository.ProductRow row, MetaLookup.Lookup lookup) {
        ProductSchema schema = mapper.toSchema(row, products.listFields(tenantId, row.id()), lookup);
        cache.put(cacheKey(tenantId, schema.code()), schema);
        return schema;
    }

    private static String cacheKey(String tenantId, String code) {
        return tenantId + "|" + code.trim().toUpperCase(Locale.ROOT);
    }
}
