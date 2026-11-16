package ac.core.service.cases;

import ac.core.common.PageQuery;
import ac.core.persistence.ImportBatchRepository;
import ac.core.persistence.ImportBatchRow;
import ac.core.persistence.ImportTaskRepository;
import ac.core.persistence.TestCaseRecord;
import ac.core.persistence.TestCaseRepository;
import ac.core.service.product.ProductCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Global test case search across every batch of the tenant.
 *
 * <p>Besides free text (test case id, trade id, remark, any stored value) the query also accepts a
 * raw id: a case id resolves to that one case, a batch id lists its cases and a task id lists the
 * cases of all its batches.
 */
@Slf4j
@Service
public class CaseSearchService {

    /** How the query was interpreted, the UI uses it to explain the result list. */
    public enum Match {
        /** Plain text matched against the stored search text. */
        TEXT,
        /** The query was a case id. */
        CASE,
        /** The query was a batch id. */
        BATCH,
        /** The query was a task id. */
        TASK,
        /** The query looked like an id but resolved to nothing. */
        NONE
    }

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** Largest task fan-out resolved at once, keeps the IN list and the page bounded. */
    private static final int MAX_SCOPE_BATCHES = 200;

    /**
     * One search hit.
     *
     * @param id          case id
     * @param testCaseId  test case id, may be generated
     * @param keyValue    value of the unique key field, {@code null} when the product has none
     * @param rowNo       row number inside the batch
     * @param productCode product code
     * @param productName product display name
     * @param batchId     owning batch
     * @param batchName   owning batch name
     * @param taskId      owning task, {@code null} for external output rows
     * @param taskName    owning task name
     */
    public record Item(String id, String testCaseId, String keyValue, Integer rowNo, String productCode,
            String productName, String batchId, String batchName, String taskId, String taskName) {
    }

    /**
     * One page of hits.
     *
     * @param match      how the query was interpreted
     * @param items      hits of the requested page
     * @param page       1-based page number
     * @param size       page size
     * @param total      total number of hits
     * @param totalPages total number of pages
     */
    public record Page(Match match, List<Item> items, int page, int size, long total, int totalPages) {
        public Page {
            items = List.copyOf(items);
        }
    }

    private final TestCaseRepository caseRepository;
    private final ImportBatchRepository batchRepository;
    private final ImportTaskRepository taskRepository;
    private final ProductCatalog products;

    public CaseSearchService(TestCaseRepository caseRepository, ImportBatchRepository batchRepository,
            ImportTaskRepository taskRepository, ProductCatalog products) {
        this.caseRepository = caseRepository;
        this.batchRepository = batchRepository;
        this.taskRepository = taskRepository;
        this.products = products;
    }

    /** Searches cases of one tenant; the query may be free text or a case, batch or task id. */
    public Page search(String tenantId, String query, PageQuery pageRequest) {
        String text = query == null ? "" : query.trim();
        PageQuery page = pageRequest == null ? PageQuery.of(1, 20) : pageRequest;
        if (text.isEmpty()) {
            return page(tenantId, null, Match.TEXT, null, page);
        }
        if (!UUID_PATTERN.matcher(text).matches()) {
            return page(tenantId, null, Match.TEXT, text, page);
        }
        Optional<TestCaseRecord> caseHit = caseRepository.find(tenantId, text);
        if (caseHit.isPresent()) {
            return page(tenantId, null, Match.CASE, null, page, caseHit.get());
        }
        if (batchRepository.find(tenantId, text).isPresent()) {
            return page(tenantId, List.of(text), Match.BATCH, null, page);
        }
        if (taskRepository.find(tenantId, text).isPresent()) {
            List<String> batchIds = batchRepository.listByTask(tenantId, text).stream()
                    .map(ImportBatchRow::id).limit(MAX_SCOPE_BATCHES).toList();
            if (batchIds.isEmpty()) {
                return new Page(Match.TASK, List.of(), page.page(), page.size(), 0, 0);
            }
            return page(tenantId, batchIds, Match.TASK, null, page);
        }
        return new Page(Match.NONE, List.of(), page.page(), page.size(), 0, 0);
    }

    private Page page(String tenantId, List<String> batchIds, Match match, String text, PageQuery page) {
        return page(tenantId, batchIds, match, text, page, null);
    }

    private Page page(String tenantId, List<String> batchIds, Match match, String text, PageQuery page,
            TestCaseRecord pinned) {
        List<TestCaseRecord> records;
        long total;
        if (pinned != null) {
            records = List.of(pinned);
            total = 1;
        } else {
            records = caseRepository.pageInBatches(tenantId, batchIds, text, page);
            total = caseRepository.countInBatches(tenantId, batchIds, text);
        }
        int totalPages = total == 0 ? 0 : (int) ((total + page.size() - 1) / page.size());
        return new Page(match, items(tenantId, records), page.page(), page.size(), total, totalPages);
    }

    /** Adds the batch, task and product labels the search result list shows. */
    private List<Item> items(String tenantId, List<TestCaseRecord> records) {
        Set<String> batchIds = new LinkedHashSet<>();
        records.forEach(record -> batchIds.add(record.batchId()));
        Map<String, ImportBatchRow> batches = new LinkedHashMap<>();
        batchRepository.listByIds(tenantId, new ArrayList<>(batchIds))
                .forEach(batch -> batches.put(batch.id(), batch));
        Map<String, String> taskNames = new LinkedHashMap<>();
        Set<String> taskIds = new LinkedHashSet<>();
        batches.values().forEach(batch -> {
            if (batch.taskId() != null) {
                taskIds.add(batch.taskId());
            }
        });
        taskRepository.listByIds(tenantId, new ArrayList<>(taskIds))
                .forEach(task -> taskNames.put(task.id(), task.name()));

        Map<String, String> productNames = new LinkedHashMap<>();
        List<Item> items = new ArrayList<>(records.size());
        for (TestCaseRecord record : records) {
            String productName = productNames.computeIfAbsent(record.productCode(),
                    code -> productName(tenantId, code));
            ImportBatchRow batch = batches.get(record.batchId());
            String taskId = batch == null ? null : batch.taskId();
            items.add(new Item(record.id(), record.testCaseId(), record.keyValue(), record.rowNo(),
                    record.productCode(), productName, record.batchId(),
                    batch == null ? record.batchId() : batch.name(),
                    taskId, taskId == null ? null : taskNames.get(taskId)));
        }
        return items;
    }

    private String productName(String tenantId, String productCode) {
        try {
            return products.require(tenantId, productCode).name();
        } catch (ac.core.common.ApiException e) {
            if (e.code() != ac.core.common.ErrorCode.NOT_FOUND) {
                log.warn("Cannot resolve the name of product {}", productCode, e);
            }
            return productCode;
        } catch (RuntimeException e) {
            log.warn("Cannot resolve the name of product {}", productCode, e);
            return productCode;
        }
    }
}
