package ac.core.service.product;

import ac.domain.analysis.SheetDetector;
import ac.domain.layout.SequenceAligner;
import ac.domain.product.ProductSchema;
import ac.domain.table.RawTable;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Picks the product definition that fits a sheet best. */
@Component
public class ProductGuesser {

    private final ProductCatalog products;

    public ProductGuesser(ProductCatalog products) {
        this.products = products;
    }

    public ProductSchema guess(String tenantId, RawTable table) {
        List<ProductSchema> candidates = new ArrayList<>(products.all(tenantId));
        ProductSchema best = null;
        int bestScore = -1;
        for (ProductSchema candidate : candidates) {
            int headerRow = SheetDetector.detectHeaderRow(candidate, table);
            if (headerRow < 1 || headerRow - 1 >= table.rowCount()) {
                continue;
            }
            int startColumn = SheetDetector.detectTradeStartColumn(candidate, table, headerRow);
            List<String> headers = table.headerTexts(headerRow - 1);
            int from = Math.max(0, startColumn - 1);
            int to = Math.min(headers.size(), from + candidate.tradeFields().size());
            if (to <= from) {
                continue;
            }
            SequenceAligner.Alignment alignment = SequenceAligner.align(headers.subList(from, to),
                    candidate.tradeFields());
            int score = alignment.matchCount() * 10 + (int) alignment.score();
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best == null) {
            throw ac.core.common.ApiException.invalid("No product definition is available");
        }
        return best;
    }
}
