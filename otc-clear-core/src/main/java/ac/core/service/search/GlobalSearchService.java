package ac.core.service.search;

import ac.core.common.ApiException;
import ac.core.common.TimeProvider;
import ac.core.persistence.SearchRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Global search over batches and cases. */
@Service
public class GlobalSearchService {

    /** Minimum length of a free text term, so a single character never scans the whole tenant. */
    public static final int MIN_TERM_LENGTH = 2;
    private static final int DEFAULT_LIMIT = 20;

    private final SearchRepository repository;

    public GlobalSearchService(SearchRepository repository) {
        this.repository = repository;
    }

    /**
     * Runs one search expression.
     *
     * @param tenantId tenant scope
     * @param rawQuery raw user input, qualifiers allowed
     * @param limit    maximum hits per group
     * @return grouped hits with the parsed expression, so the UI can explain what was searched
     */
    public SearchResult search(String tenantId, String rawQuery, Integer limit) {
        SearchQuery query = SearchQuery.parse(rawQuery);
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, 50));
        if (query.isEmpty()) {
            return new SearchResult(query, List.of(), List.of(), false);
        }
        String text = query.text();
        if (text != null && text.length() < MIN_TERM_LENGTH && query.tag() == null && query.product() == null
                && query.batchId() == null && query.caseBatch() == null && query.caseText() == null) {
            throw ApiException.invalid("Search term must be at least " + MIN_TERM_LENGTH + " characters");
        }
        List<SearchRepository.BatchHit> batches = query.wantsBatches()
                ? repository.searchBatches(tenantId, query, size + 1)
                : List.of();
        List<SearchRepository.CaseHit> cases = query.wantsCases()
                ? repository.searchCases(tenantId, query, size + 1)
                : List.of();
        boolean truncated = batches.size() > size || cases.size() > size;
        return new SearchResult(query,
                batches.size() > size ? batches.subList(0, size) : batches,
                cases.size() > size ? cases.subList(0, size) : cases,
                truncated);
    }

    /**
     * Serializes a search result for the UI.
     *
     * @param result service result
     * @return map with the parsed expression, both hit groups and display friendly fields
     */
    public static Map<String, Object> toView(SearchResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", result.query().text());
        body.put("scope", result.query().scope().name());
        Map<String, Object> qualifiers = new LinkedHashMap<>();
        qualifiers.put("tag", result.query().tag());
        qualifiers.put("product", result.query().product());
        qualifiers.put("batchId", result.query().batchId());
        qualifiers.put("batch", result.query().caseBatch());
        qualifiers.put("case", result.query().caseText());
        body.put("qualifiers", qualifiers);
        body.put("batches", result.batches().stream().map(hit -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("batchId", hit.batchId());
            view.put("taskId", hit.taskId());
            view.put("productCode", hit.productCode());
            view.put("sourceFilename", hit.sourceFilename());
            view.put("sheetName", hit.sheetName());
            view.put("note", hit.note());
            view.put("tags", tagsOf(hit.tags()));
            view.put("status", hit.status());
            view.put("totalRows", hit.totalRows());
            view.put("createdAt", TimeProvider.display(hit.createdAt()));
            return view;
        }).toList());
        body.put("cases", result.cases().stream().map(hit -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("caseId", hit.caseId());
            view.put("batchId", hit.batchId());
            view.put("productCode", hit.productCode());
            view.put("rowNo", hit.rowNo());
            view.put("testCaseId", hit.testCaseId());
            view.put("keyValue", hit.keyValue());
            view.put("formulaCount", hit.formulaCount());
            view.put("sourceFilename", hit.sourceFilename());
            return view;
        }).toList());
        body.put("totalBatches", result.batches().size());
        body.put("totalCases", result.cases().size());
        body.put("truncated", result.truncated());
        return body;
    }

    private static List<String> tagsOf(String column) {
        if (column == null || column.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(column.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .toList();
    }

    /**
     * Grouped search result.
     *
     * @param query     parsed expression
     * @param batches   batch hits
     * @param cases     case hits
     * @param truncated true when more hits existed than returned
     */
    public record SearchResult(SearchQuery query, List<SearchRepository.BatchHit> batches,
            List<SearchRepository.CaseHit> cases, boolean truncated) {

        public SearchResult {
            batches = List.copyOf(batches);
            cases = List.copyOf(cases);
        }
    }
}
