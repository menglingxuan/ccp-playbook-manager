package ac.web.api;

import ac.core.service.product.ProductCatalog;
import ac.web.config.TenantProvider;
import ac.web.dto.ProductView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Runtime product schema endpoints used by the import wizard and the catalog banner. */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductCatalog products;
    private final TenantProvider tenantProvider;

    public ProductController(ProductCatalog products, TenantProvider tenantProvider) {
        this.products = products;
        this.tenantProvider = tenantProvider;
    }

    @GetMapping
    public List<ProductView> list() {
        return products.all(tenantProvider.tenantId()).stream().map(ProductView::of).toList();
    }

    @GetMapping("/{code}")
    public ProductView detail(@PathVariable String code) {
        return ProductView.of(products.require(tenantProvider.tenantId(), code));
    }
}
