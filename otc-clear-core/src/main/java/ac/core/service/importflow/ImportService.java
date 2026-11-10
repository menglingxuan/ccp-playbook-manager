package ac.core.service.importflow;

import ac.core.common.Ids;
import ac.core.common.JsonSupport;
import ac.core.common.TimeProvider;
import ac.core.config.AppProperties;
import ac.core.persistence.ImportBatchRepository;
import ac.core.persistence.ImportBatchRow;
import ac.core.persistence.ImportTaskRepository;
import ac.core.persistence.ImportTaskRow;
import ac.core.persistence.TestCaseRecord;
import ac.core.persistence.TestCaseRepository;
import ac.core.service.baseline.BaselineStore;
import ac.core.service.cases.CaseJsonCodec;
import ac.core.service.product.ProductCatalog;
import ac.domain.analysis.ImportStopPolicy;
import ac.domain.analysis.PreviewOptions;
import ac.domain.analysis.SheetAnalysis;
import ac.domain.analysis.SheetAnalyzer;
import ac.domain.casefile.CaseRow;
import ac.domain.casefile.IssueLevel;
import ac.domain.casefile.UnresolvedFormula;
import ac.domain.casefile.ValidationIssue;
import ac.domain.layout.SheetLayout;
import ac.domain.product.ProductSchema;
import ac.domain.table.RawTable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits a wizard submission as one import task with one batch per sheet. */
@Slf4j
@Service
public class ImportService {

    private static final int INSERT_CHUNK = 500;
    private static final String TASK_KIND = "FILE_UPLOAD";

    /**
     * Layout problems that make the column mapping impossible, so the whole sheet is rejected.
     * Missing values stay row level issues: the sheet is still imported with invalid rows.
     */
    private static final Set<String> BLOCKING_LAYOUT_CODES = Set.of(
            "STRICT_ORDER_MISMATCH",
            "TRADE_BLOCK_NOT_CONTIGUOUS",
            "DUPLICATE_SOURCE_HEADER",
            "DUPLICATE_PRODUCT_HEADER");

    /**
     * Column mapping notes the wizard already shows while previewing; repeating them in the import
     * result only adds noise. Blocking errors with the same code are always kept.
     */
    private static final Set<String> PREVIEW_ONLY_CODES = Set.of(
            "UNMAPPED_TRADE_COLUMN",
            "DUPLICATE_HEADER_MAPPING",
            "TRADE_BLOCK_NOT_CONTIGUOUS");

    private static boolean isBlocking(ac.domain.casefile.ValidationIssue issue) {
        return issue.level() == IssueLevel.ERROR && BLOCKING_LAYOUT_CODES.contains(issue.code());
    }

    private static boolean isPreviewOnly(ac.domain.casefile.ValidationIssue issue) {
        return issue.level() == IssueLevel.WARN && PREVIEW_ONLY_CODES.contains(issue.code());
    }

    private final UploadStore uploadStore;
    private final PreviewService previewService;
    private final SheetCache sheetCache;
    private final ProductCatalog products;
    private final ImportTaskRepository taskRepository;
    private final ImportBatchRepository batchRepository;
    private final TestCaseRepository caseRepository;
    private final CaseJsonCodec codec;
    private final JsonSupport json;
    private final TimeProvider time;
    private final AppProperties properties;
    private final BaselineStore baselineStore;

    public ImportService(UploadStore uploadStore, PreviewService previewService, SheetCache sheetCache,
            ProductCatalog products, ImportTaskRepository taskRepository,
            ImportBatchRepository batchRepository, TestCaseRepository caseRepository, CaseJsonCodec codec, JsonSupport json, TimeProvider time, AppProperties properties,
            BaselineStore baselineStore) {
        this.uploadStore = uploadStore;
        this.previewService = previewService;
        this.sheetCache = sheetCache;
        this.products = products;
        this.taskRepository = taskRepository;
        this.batchRepository = batchRepository;
        this.caseRepository = caseRepository;
        this.codec = codec;
        this.json = json;
        this.time = time;
        this.properties = properties;
        this.baselineStore = baselineStore;
    }

    @Transactional
    public ImportResult commit(String tenantId, ImportCommitRequest request) {
        if (request.sheets().isEmpty()) {
            throw ac.core.common.ApiException.invalid("Select at least one sheet to import");
        }
        StoredUpload upload = uploadStore.require(tenantId, request.uploadId());
        LocalDateTime now = time.nowUtc();
        String taskId = Ids.newId();

        insertTask(tenantId, taskId, upload, request, now);
        taskRepository.markRunning(tenantId, taskId, now);

        List<ValidationIssue> issues = new ArrayList<>();
        List<BatchImportResult> batches = new ArrayList<>();
        int totalValid = 0;
        int totalInvalid = 0;
        int failedSheets = 0;
        Set<String> seenSheets = new HashSet<>();

        for (ImportSelection selection : request.sheets()) {
            int sheetIndex = selection.resolvedSheetIndex();
            if (!seenSheets.add(sheetIndex + "|" + selection.productCode())) {
                continue;
            }
            try {
                ProductSchema schema = products.requireImportable(tenantId, selection.productCode());
                RawTable table = previewService.loadFullSheet(tenantId, upload, sheetIndex);
                PreviewOptions options = new PreviewOptions(selection.headerRowOrAuto(),
                        selection.tradeStartColumnOrAuto(), selection.dataStartRowOrAuto(),
                        selection.dataEndRowOrAuto());
                SheetAnalysis analysis = SheetAnalyzer.analyze(schema, table, options, upload.fileName(),
                        properties.getIds().getHashLength(), properties.getIds().isNormalizeFormulaRefs(),
                        new ImportStopPolicy(request.stopAtEmptyRowEnabled(),
                                request.stopAtFirstErrorEnabled()));
                issues.addAll(analysis.issues().stream().filter(issue -> !isPreviewOnly(issue)).toList());

                if (analysis.layout() == null) {
                    failedSheets++;
                    continue;
                }
                if (analysis.issues().stream().anyMatch(issue -> isBlocking(issue))) {
                    failedSheets++;
                    continue;
                }
                if (duplicateImport(tenantId, upload, table, analysis, issues, request.force())) {
                    continue;
                }

                DuplicateFilter duplicateFilter = filterDuplicateKeys(analysis, issues,
                        request.stopAtFirstErrorEnabled());
                List<CaseRow> rows = duplicateFilter.rows();
                String batchId = Ids.newId();
                SheetLayout layout = analysis.layout();
                int invalid = analysis.invalidRows() + duplicateFilter.rejected();
                batchRepository.insert(new ImportBatchRow(batchId, tenantId, taskId, upload.uploadId(), "IMPORT",
                        null, schema.code(), upload.fileName(), upload.sha256(), upload.sourceType(),
                        table.sheetName(), layout.headerRow(), layout.tradeStartColumn(),
                        layout.tradeEndColumn(), layoutJson(layout), mappingJson(layout), schema.schemaHash(),
                        "ACTIVE", rows.size(), invalid, null, false, false, null, "system", now, now,
                        null));
                keepBaseline(tenantId, batchId, upload, issues);

                List<TestCaseRecord> records = new ArrayList<>(rows.size());
                for (CaseRow row : rows) {
                    records.add(toRecord(tenantId, batchId, schema, row, now, request));
                    issues.addAll(row.issues());
                }
                for (int start = 0; start < records.size(); start += INSERT_CHUNK) {
                    caseRepository.insertAll(records.subList(start, Math.min(records.size(), start + INSERT_CHUNK)));
                }

                totalValid += rows.size();
                totalInvalid += invalid;
                batches.add(new BatchImportResult(batchId, sheetIndex, table.sheetName(), schema.code(),
                        rows.size(), invalid, analysis.emptyRows(), "ACTIVE"));
            } catch (RuntimeException e) {
                log.warn("Import of sheet {} failed: {}", sheetIndex, e.getMessage());
                failedSheets++;
                issues.add(ValidationIssue.error("IMPORT_FAILED",
                        "Sheet " + sheetIndex + " could not be imported: " + e.getMessage()));
            }
        }

        String status = batches.isEmpty() ? "FAILED"
                : totalInvalid > 0 || failedSheets > 0 ? "PARTIAL" : "SUCCEEDED";
        String message = batches.isEmpty() ? "No sheet could be imported"
                : failedSheets > 0 ? failedSheets + " sheet(s) failed" : null;
        List<ValidationIssue> capped = capIssues(issues);
        taskRepository.finish(tenantId, taskId, status, request.sheets().size(), batches.size(), totalValid,
                totalInvalid, issuesJson(capped), message, time.nowUtc());
        sheetCache.evict(upload.uploadId());

        Map<String, Object> auditDetail = new LinkedHashMap<>();
        auditDetail.put("batches", batches.size());
        auditDetail.put("rows", totalValid);
        auditDetail.put("invalidRows", totalInvalid);

        return new ImportResult(taskId, status, totalValid, totalInvalid, batches.size(),
                batches, capped);
    }

    private String issuesJson(List<ValidationIssue> issues) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ValidationIssue issue : issues) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("level", issue.level().name());
            item.put("code", issue.code());
            item.put("message", issue.message());
            item.put("row", issue.row());
            item.put("column", issue.column());
            item.put("header", issue.header());
            item.put("fieldKey", issue.fieldKey());
            items.add(item);
        }
        return json.toJson(items);
    }

    /**
     * Keeps an immutable copy of the imported workbook so export can recalculate its formulas even
     * after the upload itself has been purged. A missing copy only degrades recalculation.
     */
    private void keepBaseline(String tenantId, String batchId, StoredUpload upload,
            List<ValidationIssue> issues) {
        if (!BaselineStore.isWorkbook(upload.sourceType())) {
            return;
        }
        if (baselineStore.save(tenantId, batchId, upload).isEmpty()) {
            issues.add(ValidationIssue.warn("BASELINE_MISSING",
                    "The workbook baseline could not be stored; formula recalculation at export "
                            + "is unavailable for this batch"));
        }
    }

    /**
     * Warns about business keys repeated inside the submitted rows. Keys imported by an earlier batch
     * are not a duplicate: every import creates its own batch and test data is imported repeatedly.
     *
     * <p>Rows whose key already appeared are rejected; with {@code stopAtFirstError} the first such row
     * also ends the sheet, so the rows behind it are neither imported nor counted as invalid.
     */
    private DuplicateFilter filterDuplicateKeys(SheetAnalysis analysis,
            List<ValidationIssue> issues, boolean stopAtFirstError) {
        List<CaseRow> rows = new ArrayList<>(analysis.rows());
        Map<String, Long> counts = new LinkedHashMap<>();
        for (CaseRow row : rows) {
            if (row.keyValue() != null) {
                counts.merge(row.keyValue(), 1L, Long::sum);
            }
        }
        List<String> repeated = counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
        if (repeated.isEmpty()) {
            return new DuplicateFilter(rows, 0);
        }
        repeated.stream().limit(10).forEach(key -> issues.add(ValidationIssue.warn("KEY_DUPLICATE",
                "Trade id '" + key + "' appears " + counts.get(key) + " times in the sheet")));
        if (!properties.getImportRules().isRejectDuplicateKey()) {
            return new DuplicateFilter(rows, 0);
        }
        Set<String> seen = new HashSet<>();
        List<CaseRow> filtered = new ArrayList<>(rows.size());
        int rejected = 0;
        CaseRow stoppedAt = null;
        for (CaseRow row : rows) {
            if (row.keyValue() == null || seen.add(row.keyValue())) {
                filtered.add(row);
                continue;
            }
            rejected++;
            if (stopAtFirstError) {
                stoppedAt = row;
                break;
            }
        }
        issues.add(ValidationIssue.error("KEY_DUPLICATE",
                rejected + " row(s) rejected because their trade id repeats in the sheet"));
        if (stoppedAt != null) {
            issues.add(ValidationIssue.warn("IMPORT_STOPPED",
                    "Import stopped before row " + stoppedAt.sourceRow() + ": trade id '"
                            + stoppedAt.keyValue() + "' repeats in the sheet, "
                            + (rows.size() - filtered.size() - 1) + " row(s) not imported"));
        }
        return new DuplicateFilter(filtered, rejected);
    }

    /** Rows kept after the in-sheet duplicate key filter plus how many rows it rejected. */
    private record DuplicateFilter(List<CaseRow> rows, int rejected) {
    }

    private boolean duplicateImport(String tenantId, StoredUpload upload, RawTable table, SheetAnalysis analysis,
            List<ValidationIssue> issues, boolean force) {
        if (force || !batchRepository.existsActiveImportForUpload(tenantId, upload.uploadId(),
                table.sheetName())) {
            return false;
        }
        AppProperties.DuplicateAction action = properties.getImportRules().getDuplicateOriginalIdAction();
        if (action == AppProperties.DuplicateAction.IGNORE) {
            return true;
        }
        ValidationIssue issue = ValidationIssue.warn("DUPLICATE_IMPORT",
                "This upload was already imported for sheet '" + table.sheetName() + "'");
        issues.add(issue);
        if (action == AppProperties.DuplicateAction.ERROR) {
            issues.add(ValidationIssue.error("DUPLICATE_IMPORT",
                    "Import cancelled because this upload was already imported").atRow(
                            analysis.layout().headerRow()));
            return true;
        }
        return false;
    }

    private TestCaseRecord toRecord(String tenantId, String batchId, ProductSchema schema, CaseRow row,
            LocalDateTime now, ImportCommitRequest request) {
        Map<String, String> managed = request.managedColumnsEnabled() ? row.managed() : Map.of();
        Map<String, String> extras = request.unmappedColumnsEnabled() ? row.extras() : Map.of();
        Map<String, String> formulas = row.formulas();
        List<UnresolvedFormula> unresolved = row.unresolvedFormulas();
        if (managed.size() != row.managed().size() || extras.size() != row.extras().size()) {
            // Managed and unmapped values are dropped: their formulas must not survive either.
            Set<String> dropped = new HashSet<>();
            if (managed.size() != row.managed().size()) {
                dropped.addAll(row.managed().keySet());
            }
            if (extras.size() != row.extras().size()) {
                dropped.addAll(row.extras().keySet());
            }
            formulas = formulas.entrySet().stream()
                    .filter(entry -> !dropped.contains(entry.getKey()))
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                            (left, right) -> left, LinkedHashMap::new));
            unresolved = unresolved.stream()
                    .filter(item -> !dropped.contains(item.key()))
                    .toList();
        }
        String managedJson = managed.isEmpty() ? null : codec.managedJson(managed);
        String extrasJson = extras.isEmpty() ? null : codec.extrasJson(extras);
        String formulaJson = formulas.isEmpty() && unresolved.isEmpty()
                ? null : codec.formulaJson(schema, formulas, unresolved);
        return new TestCaseRecord(Ids.newId(), tenantId, batchId, schema.code(), "IMPORT", row.rowNo(),
                row.sourceRow(), row.testCaseId(), row.testCaseIdSource().name(), row.originalRecordId(),
                row.businessRecordId(), row.keyValue(), codec.dataJson(schema, row), managedJson, formulaJson,
                extrasJson, formulas.size(), row.searchText(), "ACTIVE", 0, null, now, now);
    }

    private String layoutJson(SheetLayout layout) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("headerRow", layout.headerRow());
        payload.put("dataStartRow", layout.dataStartRow());
        payload.put("tradeStartColumn", layout.tradeStartColumn());
        payload.put("tradeEndColumn", layout.tradeEndColumn());
        payload.put("lastColumn", layout.lastColumn());
        payload.put("missingFields", layout.missingFields());
        payload.put("blockContiguous", layout.blockContiguous());
        return json.toJson(payload);
    }

    private String mappingJson(SheetLayout layout) {
        List<Map<String, Object>> items = new ArrayList<>();
        layout.assignments().forEach(assignment -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("column", assignment.columnNumber());
            item.put("header", assignment.header());
            item.put("kind", assignment.kind().name());
            item.put("fieldKey", assignment.fieldKey());
            item.put("managedKey", assignment.managedKey());
            items.add(item);
        });
        return json.toJson(items);
    }

    /**
     * Inserts the task row with the first free default name; a concurrent import that grabbed the
     * same name between the lookup and the insert triggers a retry instead of a failed import.
     */
    private void insertTask(String tenantId, String taskId, StoredUpload upload, ImportCommitRequest request,
            LocalDateTime now) {
        for (int attempt = 1; ; attempt++) {
            ImportTaskRow row = new ImportTaskRow(taskId, tenantId, TASK_KIND, "PENDING",
                    taskRepository.nextDefaultName(tenantId), upload.fileName(), upload.sha256(),
                    request.sheets().size(), 0, 0, 0, paramsJson(request), null, null, "system", now, null, null);
            try {
                taskRepository.insert(row);
                return;
            } catch (DuplicateKeyException e) {
                if (attempt >= 3) {
                    throw e;
                }
            }
        }
    }

    private String paramsJson(ImportCommitRequest request) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ImportSelection selection : request.sheets()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sheetIndex", selection.resolvedSheetIndex());
            item.put("productCode", selection.productCode());
            item.put("headerRow", selection.headerRowOrAuto());
            item.put("tradeStartColumn", selection.tradeStartColumnOrAuto());
            item.put("dataStartRow", selection.dataStartRowOrAuto());
            item.put("dataEndRow", selection.dataEndRowOrAuto());
            item.put("includeManagedColumns", request.managedColumnsEnabled());
            item.put("includeUnmappedColumns", request.unmappedColumnsEnabled());
            items.add(item);
        }
        return json.toJson(items);
    }

    private List<ValidationIssue> capIssues(List<ValidationIssue> issues) {
        int limit = properties.getLimits().getMaxIssueDetails();
        List<ValidationIssue> sorted = new ArrayList<>(issues);
        sorted.sort((left, right) -> {
            int byLevel = right.level().ordinal() - left.level().ordinal();
            if (byLevel != 0) {
                return byLevel;
            }
            return Integer.compare(left.row() == null ? 0 : left.row(), right.row() == null ? 0 : right.row());
        });
        if (sorted.size() <= limit) {
            return sorted;
        }
        List<ValidationIssue> capped = new ArrayList<>(sorted.subList(0, limit));
        capped.add(ValidationIssue.info("ISSUES_TRUNCATED",
                (sorted.size() - limit) + " more issue(s) were omitted"));
        return capped;
    }
}
