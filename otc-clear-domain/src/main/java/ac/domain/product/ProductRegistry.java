package ac.domain.product;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable registry of the available product schemas, ordered by registration. */
public final class ProductRegistry {

    private final Map<String, ProductSchema> byCode;

    private ProductRegistry(Map<String, ProductSchema> byCode) {
        this.byCode = Collections.unmodifiableMap(byCode);
    }

    public static ProductRegistry of(Class<?>... productClasses) {
        Map<String, ProductSchema> map = new LinkedHashMap<>();
        for (Class<?> productClass : productClasses) {
            ProductSchema schema = ProductSchemaFactory.create(productClass);
            if (map.putIfAbsent(schema.code(), schema) != null) {
                throw new IllegalStateException("Duplicate product code " + schema.code());
            }
        }
        return new ProductRegistry(map);
    }

    public Optional<ProductSchema> find(String code) {
        return Optional.ofNullable(code == null ? null : byCode.get(code.toUpperCase()));
    }

    public ProductSchema require(String code) {
        return find(code).orElseThrow(
                () -> new IllegalArgumentException("Unknown product code '" + code + "'"));
    }

    public Collection<ProductSchema> all() {
        return List.copyOf(byCode.values());
    }
}
