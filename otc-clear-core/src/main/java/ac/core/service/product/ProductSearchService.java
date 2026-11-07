package ac.core.service.product;

import ac.core.persistence.BaseProductRepository;
import ac.core.persistence.BaseProductRepository.BaseFieldRow;
import ac.core.persistence.BaseProductRepository.BaseProductRow;
import ac.core.persistence.ProductRepository;
import ac.core.persistence.ProductRepository.ProductFieldRow;
import ac.core.persistence.ProductRepository.ProductRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Global search across the product catalogue: base products, derived products and their fields.
 *
 * <p>Matches column headers, aliases, tags, remarks and product names/codes. The result is a flat
 * list ordered by kind so the UI can group it without a second round trip.
 */
@Service
public class ProductSearchService {

    /** Maximum number of hits returned for a single query. */
    static final int HIT_LIMIT = 200;

    private static final int SNIPPET_BEFORE = 24;
    private static final int SNIPPET_AFTER = 48;

    private final BaseProductRepository baseRepository;
    private final ProductRepository productRepository;
    private final MetaLookup metaLookup;

    public ProductSearchService(BaseProductRepository baseRepository, ProductRepository productRepository,
            MetaLookup metaLookup) {
        this.baseRepository = baseRepository;
        this.productRepository = productRepository;
        this.metaLookup = metaLookup;
    }

    /** Kinds returned by the search, in display order. */
    public enum Kind {
        BASE_PRODUCT, PRODUCT, BASE_FIELD, PRODUCT_FIELD
    }

    /** Attributes a hit was matched on. */
    public enum Match {
        NAME, CODE, HEADER, ALIAS, TAG, REMARK
    }

    /**
     * One search hit.
     *
     * @param kind            hit kind
     * @param platformCode    owning platform
     * @param baseProductId   owning base product id
     * @param baseProductCode owning base product code
     * @param baseProductName owning base product name
     * @param productId       product id, only for product kinds
     * @param productCode     product code, only for product kinds
     * @param productName     product name, only for product kinds
     * @param fieldId         field id, only for field kinds
     * @param header          column header, only for field kinds
     * @param tags            field tags, only for field kinds
     * @param matchedIn       attribute that matched
     * @param snippet         text around the match, already collapsed
     */
    public record Hit(Kind kind, String platformCode, String baseProductId, String baseProductCode,
            String baseProductName, String productId, String productCode, String productName, String fieldId,
            String header, List<String> tags, Match matchedIn, String snippet) {

        public Hit {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    /**
     * Search response.
     *
     * @param query    normalized query
     * @param platform platform the search was limited to, {@code null} when global
     * @param total    number of hits
     * @param hits     hits ordered by kind
     */
    public record Result(String query, String platform, int total, List<Hit> hits) {

        public Result {
            hits = List.copyOf(hits);
        }
    }

    public Result search(String tenantId, String query) {
        return search(tenantId, null, query);
    }

    /**
     * Searches the catalogue, optionally limited to one platform.
     *
     * @param platformCode platform filter, {@code null} or blank searches every platform
     */
    public Result search(String tenantId, String platformCode, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return new Result("", null, 0, List.of());
        }
        String platformFilter = platformCode == null || platformCode.isBlank()
                ? null : platformCode.trim().toUpperCase(Locale.ROOT);
        Map<String, String> tagLabels = metaLookup.load(tenantId).tagLabels();
        List<Hit> baseHits = new ArrayList<>();
        List<Hit> productHits = new ArrayList<>();
        List<Hit> baseFieldHits = new ArrayList<>();
        List<Hit> productFieldHits = new ArrayList<>();

        for (BaseProductRow base : baseRepository.listAll(tenantId)) {
            if (platformFilter != null && !platformFilter.equals(base.platformCode())) {
                continue;
            }
            if (baseHits.size() + productHits.size() + baseFieldHits.size()
                    + productFieldHits.size() >= HIT_LIMIT) {
                break;
            }
            collectBaseProduct(base, q, baseHits);
            for (BaseFieldRow field : baseRepository.listFields(tenantId, base.id())) {
                collectField(field, q, tagLabels, base, null, Kind.BASE_FIELD, baseFieldHits);
            }
            for (ProductRow product : productRepository.listByBaseProduct(tenantId, base.id())) {
                collectProduct(base, product, q, productHits);
                for (ProductFieldRow field : productRepository.listFields(tenantId, product.id())) {
                    collectProductField(field, q, tagLabels, base, product, productFieldHits);
                }
            }
        }

        List<Hit> hits = new ArrayList<>();
        hits.addAll(baseHits);
        hits.addAll(productHits);
        hits.addAll(baseFieldHits);
        hits.addAll(productFieldHits);
        if (hits.size() > HIT_LIMIT) {
            hits = hits.subList(0, HIT_LIMIT);
        }
        return new Result(q, platformFilter, hits.size(), hits);
    }

    private void collectBaseProduct(BaseProductRow base, String q, List<Hit> sink) {
        Match match = matchNameOrCode(base.name(), base.code(), q);
        if (match == null) {
            return;
        }
        String source = match == Match.NAME ? base.name() : base.code();
        sink.add(new Hit(Kind.BASE_PRODUCT, base.platformCode(), base.id(), base.code(), base.name(),
                null, null, null, null, null, List.of(), match, snippet(source, q)));
    }

    private void collectProduct(BaseProductRow base, ProductRow product, String q, List<Hit> sink) {
        Match match = matchNameOrCode(product.name(), product.code(), q);
        if (match == null) {
            return;
        }
        String source = match == Match.NAME ? product.name() : product.code();
        sink.add(new Hit(Kind.PRODUCT, base.platformCode(), base.id(), base.code(), base.name(),
                product.id(), product.code(), product.name(), null, null, List.of(), match, snippet(source, q)));
    }

    private void collectField(BaseFieldRow field, String q, Map<String, String> tagLabels,
            BaseProductRow base, ProductRow product, Kind kind, List<Hit> sink) {
        Match match = matchField(field.header(), field.aliases(), field.tags(), field.remark(), q, tagLabels);
        if (match == null) {
            return;
        }
        sink.add(new Hit(kind, base.platformCode(), base.id(), base.code(), base.name(),
                product == null ? null : product.id(), product == null ? null : product.code(),
                product == null ? null : product.name(), field.id(), field.header(), field.tags(), match,
                snippet(matchSource(match, field.header(), field.aliases(), field.tags(), field.remark(),
                        q, tagLabels), q)));
    }

    private void collectProductField(ProductFieldRow field, String q, Map<String, String> tagLabels,
            BaseProductRow base, ProductRow product, List<Hit> sink) {
        Match match = matchField(field.header(), field.aliases(), field.tags(), field.remark(), q, tagLabels);
        if (match == null) {
            return;
        }
        sink.add(new Hit(Kind.PRODUCT_FIELD, base.platformCode(), base.id(), base.code(), base.name(),
                product.id(), product.code(), product.name(), field.id(), field.header(), field.tags(), match,
                snippet(matchSource(match, field.header(), field.aliases(), field.tags(), field.remark(),
                        q, tagLabels), q)));
    }

    private static Match matchNameOrCode(String name, String code, String q) {
        if (contains(name, q)) {
            return Match.NAME;
        }
        if (contains(code, q)) {
            return Match.CODE;
        }
        return null;
    }

    private static Match matchField(String header, List<String> aliases, List<String> tags, String remark,
            String q, Map<String, String> tagLabels) {
        if (contains(header, q)) {
            return Match.HEADER;
        }
        for (String alias : aliases) {
            if (contains(alias, q)) {
                return Match.ALIAS;
            }
        }
        for (String tag : tags) {
            if (contains(tag, q) || contains(tagLabels.get(tag), q)) {
                return Match.TAG;
            }
        }
        if (contains(remark, q)) {
            return Match.REMARK;
        }
        return null;
    }

    private static String matchSource(Match match, String header, List<String> aliases, List<String> tags,
            String remark, String q, Map<String, String> tagLabels) {
        return switch (match) {
            case HEADER -> header;
            case ALIAS -> firstContaining(aliases, q, null);
            case TAG -> tagText(tags, q, tagLabels);
            case REMARK -> remark;
            case NAME, CODE -> header;
        };
    }

    private static String tagText(List<String> tags, String q, Map<String, String> tagLabels) {
        for (String tag : tags) {
            if (contains(tag, q) || contains(tagLabels.get(tag), q)) {
                String label = tagLabels.get(tag);
                return label == null ? tag : label;
            }
        }
        return String.join(", ", tags);
    }

    private static String firstContaining(List<String> values, String q, String fallback) {
        for (String value : values) {
            if (contains(value, q)) {
                return value;
            }
        }
        return fallback;
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** Returns the text around the first match, collapsed to a single line. */
    static String snippet(String text, String q) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ");
        String lower = flat.toLowerCase(Locale.ROOT);
        int index = lower.indexOf(q);
        if (index < 0) {
            return flat.length() > SNIPPET_BEFORE + SNIPPET_AFTER
                    ? flat.substring(0, SNIPPET_BEFORE + SNIPPET_AFTER) + "…" : flat;
        }
        int start = Math.max(0, index - SNIPPET_BEFORE);
        int end = Math.min(flat.length(), index + q.length() + SNIPPET_AFTER);
        String slice = flat.substring(start, end);
        return (start > 0 ? "…" : "") + slice + (end < flat.length() ? "…" : "");
    }
}
