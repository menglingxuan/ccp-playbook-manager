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
import ac.core.service.audit.AuditService;
import ac.core.service.cases.CaseJsonCodec;
import ac.core.service.product.ProductCatalog;
import ac.domain.analysis.PreviewOptions;
import ac.domain.analysis.SheetAnalysis;
import ac.domain.analysis.SheetAnalyzer;
import ac.domain.casefile.CaseRow;
import ac.domain.casefile.IssueLevel;
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
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits a wizard submission as one import task with one batch per sheet. */
@Slf4j
@Service
public class ImportService {

    private static final int INSERT_CHUNK = 500;
    private static final String TASK_KIND = "FILE_UPLOAD";

    private final UploadStore uploadStore;
    private final PreviewService previewService;
    private final SheetCache sheetCache;
    private final ProductCatalog products;
    private final ImportTaskRepository taskRepository;
    private final ImportBatchRepository batchRepository;
    private final TestCaseRepository caseRepository;
    private final CaseJsonCodec codec;
    private final AuditService audit;
    private final JsonSupport json;
    private final TimeProvider time;
    private final AppProperties properties;

    public ImportService(UploadStore uploadStore, PreviewService previewService, SheetCache sheetCache,
            ProductCatalog products, ImportTaskRepository taskRepository,
            ImportBatchRepository batchRepository, TestCaseRepository caseRepository, CaseJsonCodec codec,
            AuditService audit, JsonSupport json, TimeProvider time, AppProperties properties) {
        this.uploadStore = uploadStore;
        this.previewService = previewService;
        this.sheetCache = sheetCache;
        this.products = products;
        this.taskRepository = taskRepository;
        this.batchRepository = batchRepository;
        this.caseRepository = caseRepository;
        this.codec = codec;
        this.audit = audit;
        this.json = json;
        this.time = time;
        this.properties = properties;
    }

    @Transactional
    public ImportResult commit(String tenantId, ImportCommitRequest request) {
        if (request.sheets().isEmpty()) {
            throw ac.core.common.ApiException.invalid("Select at least one sheet to import");
        }
        StoredUpload upload = uploadStore.require(tenantId, request.uploadId());
        LocalDateTime now = time.nowUtc();
        String taskId = Ids.newId();

        taskRepository.insert(new ImportTaskRow(taskId, tenantId, TASK_KIND, "PENDING", upload.fileName(),
                upload.sha256(), request.sheets().size(), 0, 0, 0, paramsJson(request), null, null, "system", now,
                null, null));
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
                ProductSchema schema = products.require(tenantId, selection.productCode());
                RawTable table = previewService.loadFullSheet(tenantId, upload, sheetIndex);
                PreviewOptions options = new PreviewOptions(selection.headerRowOrAuto(),
                        selection.tradeStartColumnOrAuto(), selection.dataStartRowOrAuto());
                SheetAnalysis analysis = SheetAnalyzer.analyze(schema, table, options, upload.fileName(),
                        properties.getIds().getHashLength(), properties.getIds().isNormalizeFormulaRefs());
                issues.addAll(analysis.issues());

                if (analysis.layout() == null) {
                    failedSheets++;
                    continue;
                }
                if (analysis.issues().stream()
                        .anyMatch(issue -> "STRICT_ORDER_MISMATCH".equals(issue.code()))) {
                    failedSheets++;
                    continue;
                }
                if (duplicateImport(tenantId, upload, table, analysis, issues, request.force())) {
                    continue;
                }

                List<CaseRow> rows = filterDuplicateKeys(tenantId, schema, analysis, issues);
                String batchId = Ids.newId();
                SheetLayout layout = analysis.layout();
                batchRepository.insert(new ImportBatchRow(batchId, tenantId, taskId, "IMPORT", null,
                        schema.code(), upload.fileName(), upload.sha256(), upload.sourceType(), table.sheetName(),
                        layout.headerRow(), layout.tradeStartColumn(), layout.tradeEndColumn(), layoutJson(layout),
                        mappingJson(layout), schema.schemaHash(), "ACTIVE", rows.size(),
                        analysis.invalidRows() + (analysis.rows().size() - rows.size()), null, "system", now, now,
                        null));

                List<TestCaseRecord> records = new ArrayList<>(rows.size());
                for (CaseRow row : rows) {
                    records.add(toRecord(tenantId, batchId, schema, row, now));
                    issues.addAll(row.issues());
                }
                for (int start = 0; start < records.size(); start += INSERT_CHUNK) {
                    caseRepository.insertAll(records.subList(start, Math.min(records.size(), start + INSERT_CHUNK)));
                }

                int invalid = analysis.invalidRows() + (analysis.rows().size() - rows.size());
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
        audit.record(tenantId, AuditService.ENTITY_TASK, taskId, "IMPORT", auditDetail);

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

    private List<CaseRow> filterDuplicateKeys(String tenantId, ProductSchema schema, SheetAnalysis analysis,
            List<ValidationIssue> issues) {
        List<CaseRow> rows = new ArrayList<>(analysis.rows());
        List<String> keys = rows.stream().map(CaseRow::keyValue).filter(Objects::nonNull).distinct().toList();
        if (keys.isEmpty()) {
            return rows;
        }
        List<String> existing = caseRepository.existingKeys(tenantId, schema.code(), keys);
        if (existing.isEmpty()) {
            return rows;
        }
        existing.stream().limit(10).forEach(key -> issues.add(ValidationIssue.warn("KEY_DUPLICATE",
                "Trade id '" + key + "' already exists for product " + schema.code())));
        if (!properties.getImportRules().isRejectDuplicateKey()) {
            return rows;
        }
        List<CaseRow> filtered = rows.stream()
                .filter(row -> row.keyValue() == null || !existing.contains(row.keyValue()))
                .toList();
        issues.add(ValidationIssue.error("KEY_DUPLICATE",
                existing.size() + " row(s) rejected because their trade id already exists"));
        return filtered;
    }

    private boolean duplicateImport(String tenantId, StoredUpload upload, RawTable table, SheetAnalysis analysis,
            List<ValidationIssue> issues, boolean force) {
        if (force || !batchRepository.existsActiveImport(tenantId, upload.sha256(), table.sheetName())) {
            return false;
        }
        AppProperties.DuplicateAction action = properties.getImportRules().getDuplicateOriginalIdAction();
        if (action == AppProperties.DuplicateAction.IGNORE) {
            return true;
        }
        ValidationIssue issue = ValidationIssue.warn("DUPLICATE_IMPORT",
                "File '" + upload.fileName() + "' sheet '" + table.sheetName() + "' was already imported");
        issues.add(issue);
        if (action == AppProperties.DuplicateAction.ERROR) {
            issues.add(ValidationIssue.error("DUPLICATE_IMPORT",
                    "Import cancelled because the sheet was already imported").atRow(analysis.layout().headerRow()));
            return true;
        }
        return false;
    }

    private TestCaseRecord toRecord(String tenantId, String batchId, ProductSchema schema, CaseRow row,
            LocalDateTime now) {
        String managedJson = row.managed().isEmpty() ? null : codec.managedJson(row.managed());
        String extrasJson = row.extras().isEmpty() ? null : codec.extrasJson(row.extras());
        String formulaJson = row.formulas().isEmpty() && row.unresolvedFormulas().isEmpty()
                ? null : codec.formulaJson(schema, row.formulas(), row.unresolvedFormulas());
        return new TestCaseRecord(Ids.newId(), tenantId, batchId, schema.code(), "IMPORT", row.rowNo(),
                row.sourceRow(), row.testCaseId(), row.testCaseIdSource().name(), row.originalRecordId(),
                row.businessRecordId(), row.keyValue(), codec.dataJson(schema, row), managedJson, formulaJson,
                extrasJson, row.formulas().size(), row.searchText(), "ACTIVE", 0, null, now, now);
    }

    private String layoutJson(SheetLayout layout) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("headerRow", layout.headerRow());
        payload.put("dataStartRow", layout.dataStartRow());
        payload.put("tradeStartColumn", layout.tradeStartColumn());
        payload.put("tradeEndColumn", layout.tradeEndColumn());
        payload.put("lastColumn", layout.lastColumn());
        payload.put("missingFields", layout.missingFields());
        payload.put("ambiguous", layout.ambiguous());
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
            item.put("score", assignment.score());
            items.add(item);
        });
        return json.toJson(items);
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
