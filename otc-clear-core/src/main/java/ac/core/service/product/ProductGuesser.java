package ac.core.service.product;

import ac.domain.analysis.SheetDetector;
import ac.domain.product.ProductSchema;
import ac.domain.table.RawTable;
import java.util.List;
import org.springframework.stereotype.Component;

/** Picks the product definition that fits a sheet best. */
@Component
public class ProductGuesser {

    private final ProductCatalog products;

    public ProductGuesser(ProductCatalog products) {
        this.products = products;
    }

    /**
     * Returns the product whose field names cover most headers of the sheet.
     *
     * <p>Only products with a valid definition are considered, because an invalid definition cannot
     * be imported.
     */
    public ProductSchema guess(String tenantId, RawTable table) {
        ProductSchema best = null;
        int bestScore = -1;
        for (ProductSchema candidate : products.all(tenantId)) {
            if (!candidate.definitionValid()) {
                continue;
            }
            int headerRow = SheetDetector.detectHeaderRow(candidate, table);
            if (headerRow < 1 || headerRow - 1 >= table.rowCount()) {
                continue;
            }
            int matched = SheetDetector.matchedFields(candidate, table.headerTexts(headerRow - 1)).size();
            if (matched > bestScore) {
                bestScore = matched;
                best = candidate;
            }
        }
        if (best == null) {
            throw ac.core.common.ApiException.invalid("No product definition is available");
        }
        return best;
    }
}

