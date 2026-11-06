package ac.core.service.product;

import ac.core.common.ApiException;
import ac.domain.product.FieldDef;
import ac.domain.product.ProductRegistry;
import ac.domain.product.ProductSchema;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Exposes the configured product schemas to services and controllers. */
@Component
public class ProductCatalog {

    private final ProductRegistry registry;

    public ProductCatalog(ProductRegistry registry) {
        this.registry = registry;
    }

    public List<ProductSchema> all() {
        return List.copyOf(registry.all());
    }

    public Optional<ProductSchema> find(String code) {
        return registry.find(code);
    }

    public ProductSchema require(String code) {
        if (code == null || code.isBlank()) {
            throw ApiException.invalid("Product code is required");
        }
        return registry.find(code).orElseThrow(
                () -> ApiException.notFound("Unknown product '" + code + "'"));
    }

    public FieldDef requireField(String productCode, String fieldKey) {
        return require(productCode).requireField(fieldKey);
    }
}
