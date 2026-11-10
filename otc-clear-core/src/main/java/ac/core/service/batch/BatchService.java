package ac.core.service.batch;

import ac.core.common.ApiException;
import ac.core.common.PageQuery;
import ac.core.common.PageResult;
import ac.core.common.TimeProvider;
import ac.core.persistence.BatchFilter;
import ac.core.persistence.CaseFilter;
import ac.core.persistence.ImportBatchRepository;
import ac.core.persistence.ImportBatchRow;
import ac.core.persistence.TestCaseRepository;
import ac.core.service.cases.CaseSummary;
import ac.core.service.cases.CaseService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Batch level queries and mutations. */
@Service
public class BatchService {

    /** Upper bound of the user tags of one batch. */
    public static final int MAX_TAGS = 10;
    /** Upper bound of a single tag. */
    public static final int MAX_TAG_LENGTH = 32;
    private static final int MAX_NOTE_LENGTH = 500;

    private final ImportBatchRepository batchRepository;
    private final TestCaseRepository caseRepository;
    private final CaseService caseService;
    private final ac.core.persistence.ImportTaskRepository taskRepository;
    private final TimeProvider time;

    public BatchService(ImportBatchRepository batchRepository, TestCaseRepository caseRepository,
            CaseService caseService, ac.core.persistence.ImportTaskRepository taskRepository, TimeProvider time) {
        this.batchRepository = batchRepository;
        this.caseRepository = caseRepository;
        this.caseService = caseService;
        this.taskRepository = taskRepository;
        this.time = time;
    }

    public PageResult<ImportBatchRow> search(String tenantId, BatchFilter filter) {
        var items = batchRepository.search(tenantId, filter);
        long total = batchRepository.count(tenantId, filter);
        return new PageResult<>(items, filter.page().page(), filter.page().size(), total);
    }

    /**
     * Counters shown next to the console filters. Every dimension is counted with the other filters
     * applied, so the numbers always add up to the list the user would get by clicking them.
     */
    public BatchFacets facets(String tenantId, BatchFilter filter) {
        BatchFilter unpolarized = filter.withFavorite(null).withPinned(null);
        long total = batchRepository.count(tenantId, unpolarized);
        long favorite = batchRepository.count(tenantId, unpolarized.withFavorite(Boolean.TRUE));
        long pinned = batchRepository.count(tenantId, unpolarized.withPinned(Boolean.TRUE));
        List<FacetCount> products = toCounts(batchRepository.countByColumn(tenantId, filter, "product_code"));
        List<FacetCount> kinds = toCounts(batchRepository.countByColumn(tenantId, filter, "kind"));
        List<FacetCount> statuses = toCounts(batchRepository.countByColumn(tenantId, filter, "status"));
        List<FacetCount> tags = tagCounts(batchRepository.tagColumn(tenantId, filter));
        return new BatchFacets(total, favorite, pinned, products, kinds, statuses, tags);
    }

    /** Batches of the dock chips: pinned ones first, then the most recent ones. */
    public List<ImportBatchRow> dock(String tenantId, int limit, boolean includeDeleted) {
        int size = Math.max(1, Math.min(limit, 30));
        return batchRepository.dock(tenantId, size, includeDeleted);
    }

    public ImportBatchRow require(String tenantId, String batchId) {
        return batchRepository.find(tenantId, batchId)
                .filter(row -> !"DELETED".equals(row.status()))
                .orElseThrow(() -> ApiException.notFound("Batch " + batchId + " does not exist"));
    }

    public PageResult<CaseSummary> cases(String tenantId, String batchId, CaseFilter filter) {
        require(tenantId, batchId);
        return caseService.search(tenantId, filter);
    }

    /** Case counters of one batch, used by the sidebar summary. */
    public Map<String, Object> caseSummary(String tenantId, String batchId, String query, Boolean hasFormula) {
        require(tenantId, batchId);
        PageQuery probe = PageQuery.of(1, 1);
        long total = caseService.search(tenantId, new CaseFilter(batchId, query, false, "row", probe)).total();
        long withFormula = caseService.search(tenantId, new CaseFilter(batchId, query, true, "row", probe)).total();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", total);
        summary.put("withFormula", withFormula);
        return summary;
    }

    public void updateNote(String tenantId, String batchId, String note) {
        require(tenantId, batchId);
        batchRepository.updateNote(tenantId, batchId, note, time.nowUtc());
    }

    /**
     * Updates the console metadata of one batch; missing fields keep their stored value.
     *
     * @param favorite new favorite flag, {@code null} to keep the current one
     * @param pinned   new pinned flag, {@code null} to keep the current one
     * @param tags     replacement tag list, {@code null} to keep the current tags
     * @param note     replacement note, {@code null} to keep the current note
     */
    @Transactional
    public ImportBatchRow updateMeta(String tenantId, String batchId, Boolean favorite, Boolean pinned,
            List<String> tags, String note) {
        ImportBatchRow current = require(tenantId, batchId);
        boolean newFavorite = favorite == null ? current.favorite() : favorite;
        boolean newPinned = pinned == null ? current.pinned() : pinned;
        String newNote = note == null ? current.note() : normalizeNote(note);
        String newTags = tags == null ? current.tags() : encodeTags(tags);
        batchRepository.updateMeta(tenantId, batchId, newNote, newFavorite, newPinned, newTags, time.nowUtc());
        return batchRepository.find(tenantId, batchId).orElse(current);
    }

    @Transactional
    public void delete(String tenantId, String batchId) {
        ImportBatchRow batch = require(tenantId, batchId);
        LocalDateTime now = time.nowUtc();
        caseRepository.softDeleteByBatch(tenantId, batchId, now);
        batchRepository.softDelete(tenantId, batchId, now);
        // the batch no longer counts towards its task (clones are part of that list as well)
        taskRepository.adjustBatchCount(tenantId, batch.taskId(), -1);
    }

    public int activeCaseCount(String tenantId, String batchId) {
        return caseRepository.countByBatch(tenantId, batchId);
    }

    /**
     * Normalizes tags: trimmed, case preserved, duplicates removed, at most {@link #MAX_TAGS} tags of
     * {@link #MAX_TAG_LENGTH} characters.
     */
    static String encodeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        List<String> normalized = new ArrayList<>();
        for (String raw : tags) {
            if (raw == null) {
                continue;
            }
            String tag = raw.trim();
            if (tag.isEmpty()) {
                continue;
            }
            if (tag.indexOf(',') >= 0) {
                throw ApiException.invalid("A tag must not contain a comma: '" + tag + "'");
            }
            if (tag.length() > MAX_TAG_LENGTH) {
                throw ApiException.invalid("A tag must be at most " + MAX_TAG_LENGTH + " characters");
            }
            boolean duplicate = normalized.stream().anyMatch(tag::equalsIgnoreCase);
            if (!duplicate) {
                normalized.add(tag);
            }
        }
        if (normalized.size() > MAX_TAGS) {
            throw ApiException.invalid("A batch may carry at most " + MAX_TAGS + " tags");
        }
        return normalized.isEmpty() ? "" : "," + String.join(",", normalized) + ",";
    }

    private static String normalizeNote(String note) {
        String trimmed = note.trim();
        if (trimmed.length() > MAX_NOTE_LENGTH) {
            throw ApiException.invalid("Note must be at most " + MAX_NOTE_LENGTH + " characters");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<FacetCount> toCounts(Map<String, Long> counts) {
        return counts.entrySet().stream()
                .filter(entry -> !entry.getKey().isBlank())
                .filter(entry -> !"EXTERNAL_OUTPUT".equals(entry.getKey()))
                .map(entry -> new FacetCount(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static List<FacetCount> tagCounts(List<String> tagColumns) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String column : tagColumns) {
            if (column == null || column.isBlank()) {
                continue;
            }
            for (String tag : column.split(",")) {
                String value = tag.trim();
                if (!value.isEmpty()) {
                    counts.merge(value, 1L, Long::sum);
                }
            }
        }
        return counts.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()) == 0
                        ? left.getKey().compareToIgnoreCase(right.getKey())
                        : Long.compare(right.getValue(), left.getValue()))
                .map(entry -> new FacetCount(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * Facet counters of the console.
     *
     * @param total    batches matching the filters, ignoring the favorite/pinned toggles
     * @param favorite batches of that set which are favorites
     * @param pinned   batches of that set which are pinned
     */
    public record BatchFacets(long total, long favorite, long pinned, List<FacetCount> products,
            List<FacetCount> kinds, List<FacetCount> statuses, List<FacetCount> tags) {

        public BatchFacets {
            products = List.copyOf(products);
            kinds = List.copyOf(kinds);
            statuses = List.copyOf(statuses);
            tags = List.copyOf(tags);
        }
    }

    /**
     * One facet value with its count.
     *
     * @param value raw value ({@code null} values are dropped)
     * @param count number of batches carrying it
     */
    public record FacetCount(String value, long count) {
    }
}
