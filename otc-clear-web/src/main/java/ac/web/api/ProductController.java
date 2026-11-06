package ac.web.api;

import ac.core.service.product.ProductCatalog;
import ac.web.dto.ProductView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Product definition endpoints. */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductCatalog products;

    public ProductController(ProductCatalog products) {
        this.products = products;
    }

    @GetMapping
    public List<ProductView> list() {
        return products.all().stream().map(ProductView::of).toList();
    }

    @GetMapping("/{code}")
    public ProductView detail(@PathVariable String code) {
        return ProductView.of(products.require(code));
    }
}
