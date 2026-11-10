package ac.core.service.export;

import ac.core.common.ApiException;
import ac.core.common.Ids;
import ac.core.common.JsonSupport;
import ac.core.common.PageQuery;
import ac.core.common.PageResult;
import ac.core.common.TimeProvider;
import ac.core.persistence.ExportFilter;
import ac.core.persistence.ExportJobRepository;
import ac.core.persistence.ExportJobRow;
import ac.core.persistence.ImportBatchRepository;
import ac.core.persistence.ImportBatchRow;
import ac.core.persistence.TestCaseRecord;
import ac.core.persistence.TestCaseRepository;
import ac.core.service.DataLayout;
import ac.core.service.baseline.BaselineStore;
import ac.core.service.cases.CaseJsonCodec;
import ac.core.service.cases.CaseService;
import ac.core.service.product.ProductCatalog;
import ac.core.service.settings.SettingsService;
import ac.domain.casefile.ValidationIssue;
import ac.domain.export.CsvFormatOptions;
import ac.domain.export.CsvWriter;
import ac.domain.export.ManagedColumnScope;
import ac.domain.product.FieldCodec;
import ac.domain.product.FieldDef;
import ac.domain.product.ProductSchema;
import ac.domain.table.WorkbookRecalculator;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds CSV exports with a stable column order, including duplicated headers. */
@Slf4j
@Service
public class ExportService {

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    public static final String KNOWN_TESTCASE_KEY = "_TestCaseId";

    private final CaseService caseService;
    private final TestCaseRepository caseRepository;
    private final ImportBatchRepository batchRepository;
    private final ProductCatalog products;
    private final CaseJsonCodec codec;
    private final JsonSupport json;
    private final TimeProvider time;
    private final DataLayout dataLayout;
    private final ExportJobRepository exportRepository;
    private final SettingsService settings;
    private final BaselineStore baselines;

    public ExportService(CaseService caseService, TestCaseRepository caseRepository,
            ImportBatchRepository batchRepository, ProductCatalog products, CaseJsonCodec codec,
            JsonSupport json, TimeProvider time, DataLayout dataLayout, ExportJobRepository exportRepository,
            SettingsService settings, BaselineStore baselines) {
        this.caseService = caseService;
        this.caseRepository = caseRepository;
        this.batchRepository = batchRepository;
        this.products = products;
        this.codec = codec;
        this.json = json;
        this.time = time;
        this.dataLayout = dataLayout;
        this.exportRepository = exportRepository;
        this.settings = settings;
        this.baselines = baselines;
    }

    public ExportPrecheck precheck(String tenantId, ExportRequest request) {
        List<ValidationIssue> issues = new ArrayList<>();
        return precheck(tenantId, request, issues);
    }

    /** Precheck that also reports the recalculation warnings it produced. */
    private ExportPrecheck precheck(String tenantId, ExportRequest request, List<ValidationIssue> issues) {
        Selection selection = select(tenantId, request);
        CsvFormatOptions options = options(tenantId, request);
        List<Column> columns = columns(tenantId, request, selection);
        Recalculated recalculated = recalculate(tenantId, request, selection, issues);

        Set<String> keys = new LinkedHashSet<>();
        for (TestCaseRecord record : selection.records()) {
            for (FieldDef required : selection.schema().requiredFields()) {
                Map<String, Object> data = json.toMap(record.dataJson());
                Object value = data.get(required.key());
                if (value == null || String.valueOf(value).isBlank()) {
                    issues.add(ValidationIssue.error("REQUIRED_VALUE_MISSING",
                                    "Required field '" + required.header() + "' is empty")
                            .atRow(record.rowNo()).forField(required.key()));
                }
            }
            if (record.keyValue() != null && !record.keyValue().isBlank()) {
                if (!keys.add(record.keyValue())) {
                    issues.add(ValidationIssue.error("KEY_DUPLICATE",
                                    "Trade id '" + record.keyValue() + "' appears more than once")
                            .atRow(record.rowNo()));
                }
            }
            if (request.batchId() != null) {
                batchRepository.find(tenantId, request.batchId())
                        .filter(batch -> batch.schemaHash() != null
                                && !batch.schemaHash().equals(selection.schema().schemaHash()))
                        .ifPresent(batch -> issues.add(ValidationIssue.warn("SCHEMA_HASH_MISMATCH",
                                "The product definition changed since this batch was imported")));
            }
        }

        List<String> headers = columns.stream().map(Column::header).toList();
        TestCaseRecord first = selection.records().isEmpty() ? null : selection.records().get(0);
        List<String> sample = first == null
                ? List.of()
                : columns.stream().map(column -> cellValue(selection.schema(),
                        json.toMap(first.dataJson()), toStringMap(first), column,
                        recalculated.values().getOrDefault(first.id(), Map.of()))).toList();
        boolean valid = issues.stream().noneMatch(issue -> issue.level() == ac.domain.casefile.IssueLevel.ERROR);
        return new ExportPrecheck(valid && !selection.records().isEmpty(), selection.records().size(),
                headers, sample, issues);
    }

    @Transactional
    public ExportResult export(String tenantId, ExportRequest request) {
        Selection selection = select(tenantId, request);
        if (selection.records().isEmpty()) {
            throw ApiException.invalid("Nothing to export");
        }
        // Precheck only validates stored values; recalculation runs once below, on the real content.
        ExportPrecheck precheck = precheck(tenantId, new ExportRequest(request.batchId(),
                request.caseIds(), request.options(), Boolean.FALSE));
        if (!precheck.valid()) {
            throw new ApiException(ac.core.common.ErrorCode.INVALID_REQUEST,
                    "Export precheck failed", Map.of("issues", precheck.issues()));
        }

        CsvFormatOptions options = options(tenantId, request);
        List<ValidationIssue> issues = new ArrayList<>();
        CsvContent content = content(tenantId, request, issues);
        List<String> headers = content.headers();
        List<List<String>> rows = content.rows();

        LocalDate today = LocalDate.now(TimeProvider.DISPLAY_ZONE);
        String fileName = "%s_%s_%s.csv".formatted(selection.schema().code(),
                Ids.shortId(request.batchId() != null ? request.batchId() : Ids.newId()),
                LocalDateTime.now(TimeProvider.DISPLAY_ZONE).format(FILE_STAMP));
        Path directory = dataLayout.exportsDir(tenantId, today);
        Path temp = directory.resolve(fileName + ".tmp");
        Path target = dataLayout.resolveInside(directory, fileName);
        try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            CsvWriter.write(writer, options, headers, rows);
        } catch (IOException e) {
            throw new ApiException(ac.core.common.ErrorCode.INTERNAL_ERROR,
                    "Cannot write export file: " + e.getMessage());
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ApiException(ac.core.common.ErrorCode.INTERNAL_ERROR,
                    "Cannot finalize export file: " + e.getMessage());
        }

        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            size = 0;
        }
        String exportId = Ids.newId();
        String relativePath = dataLayout.tenantRoot(tenantId).relativize(target).toString().replace('\\', '/');
        ExportJobRow job = new ExportJobRow(exportId, tenantId, selection.schema().code(),
                request.batchId() != null ? "BATCH" : "SELECTION", request.batchId(), rows.size(), fileName,
                relativePath, size, json.toJson(Map.of(
                        "delimiter", String.valueOf(options.delimiter()),
                        "quoteAll", options.quoteAll(),
                        "managedColumns", options.managedColumns().name(),
                        "bom", options.bom(),
                        "lineEnding", options.lineEnding().equals("\n") ? "LF" : "CRLF")),
                "COMPLETED", null, "system", time.nowUtc());
        exportRepository.insert(job);

        return new ExportResult(exportId, fileName, rows.size(), size, TimeProvider.display(job.createdAt()),
                issues.stream().map(ValidationIssue::message).toList());
    }

    public PageResult<ExportJobRow> history(String tenantId, PageQuery page) {
        return history(tenantId, new ExportFilter(page));
    }

    /** Filtered history of the export management panel. */
    public PageResult<ExportJobRow> history(String tenantId, ExportFilter filter) {
        return new PageResult<>(exportRepository.page(tenantId, filter), filter.page().page(),
                filter.page().size(), exportRepository.count(tenantId, filter));
    }

    /** True when the stored CSV of one export is still on disk. */
    public boolean fileExists(String tenantId, ExportJobRow job) {
        try {
            Path path = dataLayout.resolveInside(dataLayout.tenantRoot(tenantId), job.storagePath());
            return Files.isRegularFile(path);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Removes one history entry together with its stored file. */
    @Transactional
    public void delete(String tenantId, String exportId) {
        ExportJobRow job = requireJob(tenantId, exportId);
        try {
            Path path = dataLayout.resolveInside(dataLayout.tenantRoot(tenantId), job.storagePath());
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot delete export file of {}: {}", exportId, e.toString());
        }
        exportRepository.delete(tenantId, exportId);
    }

    public ExportJobRow requireJob(String tenantId, String exportId) {
        return exportRepository.find(tenantId, exportId)
                .orElseThrow(() -> ApiException.notFound("Export " + exportId + " does not exist"));
    }

    /** Resolves the download path and verifies it stays inside the tenant directory. */
    public Path requireFile(String tenantId, String exportId) {
        ExportJobRow job = requireJob(tenantId, exportId);
        Path path = dataLayout.resolveInside(dataLayout.tenantRoot(tenantId), job.storagePath());
        if (!Files.isRegularFile(path)) {
            throw ApiException.notFound("Export file is no longer available");
        }
        return path;
    }

    private CsvFormatOptions options(String tenantId, ExportRequest request) {
        return request.options() != null ? request.options() : settings.exportOptions(tenantId);
    }

    /**
     * Rendered CSV content without touching the file system.
     *
     * @param headers column headers in output order
     * @param rows    formatted values
     */
    public record CsvContent(List<String> headers, List<List<String>> rows) {

        public CsvContent {
            headers = List.copyOf(headers);
            rows = List.copyOf(rows);
        }
    }

    /**
     * Rendered CSV content, optionally with freshly recalculated formula values.
     *
     * @param issues collects recalculation warnings so callers can surface them
     */
    public CsvContent content(String tenantId, ExportRequest request, List<ValidationIssue> issues) {
        Selection selection = select(tenantId, request);
        List<Column> columns = columns(tenantId, request, selection);
        List<String> headers = columns.stream().map(Column::header).toList();
        Recalculated recalculated = recalculate(tenantId, request, selection, issues);
        List<List<String>> rows = new ArrayList<>(selection.records().size());
        for (TestCaseRecord record : selection.records()) {
            Map<String, Object> data = json.toMap(record.dataJson());
            Map<String, String> managed = toStringMap(record);
            Map<String, String> computed = recalculated.values().getOrDefault(record.id(), Map.of());
            List<String> row = new ArrayList<>(columns.size());
            for (Column column : columns) {
                row.add(cellValue(selection.schema(), data, managed, column, computed));
            }
            rows.add(row);
        }
        return new CsvContent(headers, rows);
    }

    public String renderText(CsvContent content, CsvFormatOptions options) {
        return CsvWriter.toText(options, content.headers(), content.rows());
    }

    private Selection select(String tenantId, ExportRequest request) {
        if (request.batchId() != null && !request.caseIds().isEmpty()) {
            throw ApiException.invalid("Choose either a batch or a case selection");
        }
        List<TestCaseRecord> records;
        if (request.batchId() != null) {
            batchRepository.find(tenantId, request.batchId())
                    .orElseThrow(() -> ApiException.notFound("Batch " + request.batchId() + " does not exist"));
            records = caseRepository.listForExport(tenantId, request.batchId());
        } else if (!request.caseIds().isEmpty()) {
            records = caseService.requireAll(tenantId, request.caseIds());
        } else {
            throw ApiException.invalid("Choose a batch or select at least one case");
        }
        Set<String> productCodes = new LinkedHashSet<>();
        records.forEach(record -> productCodes.add(record.productCode()));
        if (productCodes.size() > 1) {
            throw ApiException.invalid("Selected cases must belong to the same product");
        }
        ProductSchema schema = products.require(tenantId, productCodes.isEmpty()
                ? batchProduct(tenantId, request) : productCodes.iterator().next());
        return new Selection(schema, records);
    }

    private String batchProduct(String tenantId, ExportRequest request) {
        if (request.batchId() == null) {
            throw ApiException.invalid("No cases selected");
        }
        return batchRepository.find(tenantId, request.batchId())
                .map(batch -> batch.productCode())
                .orElseThrow(() -> ApiException.notFound("Batch does not exist"));
    }

    private List<Column> columns(String tenantId, ExportRequest request, Selection selection) {
        ManagedColumnScope scope = options(tenantId, request).managedColumns();
        List<Column> columns = new ArrayList<>();
        if (scope == ManagedColumnScope.ALL) {
            appendSourceOrder(columns, tenantId, request, selection);
            return columns;
        }
        for (FieldDef field : selection.schema().tradeFields()) {
            columns.add(Column.trade(field));
        }
        if (scope == ManagedColumnScope.KNOWN) {
            boolean hasTestcaseId = selection.records().stream()
                    .map(record -> json.toMapOrEmpty(record.managedJson()))
                    .anyMatch(managed -> managed.containsKey(KNOWN_TESTCASE_KEY));
            if (hasTestcaseId) {
                columns.add(Column.managed(KNOWN_TESTCASE_KEY, KNOWN_TESTCASE_KEY));
            }
        }
        return columns;
    }

    private void appendSourceOrder(List<Column> columns, String tenantId, ExportRequest request,
            Selection selection) {
        Map<String, Column> managed = new LinkedHashMap<>();
        Set<String> emitted = new LinkedHashSet<>();
        if (request.batchId() != null) {
            batchRepository.find(tenantId, request.batchId()).ifPresent(batch -> {
                for (Object item : json.toList(batch.columnMappingJson() == null ? "[]" : batch.columnMappingJson())) {
                    if (item instanceof Map<?, ?> map) {
                        String kind = String.valueOf(map.get("kind"));
                        String header = map.get("header") == null ? "" : String.valueOf(map.get("header"));
                        String managedKey = map.get("managedKey") == null ? null : String.valueOf(map.get("managedKey"));
                        String fieldKey = map.get("fieldKey") == null ? null : String.valueOf(map.get("fieldKey"));
                        if ("MANAGED".equals(kind) && managedKey != null) {
                            columns.add(Column.managed(header.isBlank() ? managedKey : header, managedKey));
                        } else if ("TRADE".equals(kind) && fieldKey != null && emitted.add(fieldKey)) {
                            FieldDef field = selection.schema().byKey().get(fieldKey);
                            if (field != null) {
                                columns.add(Column.trade(field));
                            }
                        }
                    }
                }
            });
            if (!columns.isEmpty()) {
                return;
            }
        }
        for (FieldDef field : selection.schema().tradeFields()) {
            columns.add(Column.trade(field));
        }
        for (TestCaseRecord record : selection.records()) {
            json.toMapOrEmpty(record.managedJson()).forEach((key, value) ->
                    managed.computeIfAbsent(key, name -> Column.managed(name, name)));
        }
        columns.addAll(managed.values());
    }

    private Map<String, String> toStringMap(TestCaseRecord record) {
        Map<String, String> result = new LinkedHashMap<>();
        json.toMapOrEmpty(record.managedJson()).forEach((key, value) ->
                result.put(key, value == null ? "" : String.valueOf(value)));
        return result;
    }

    private record Selection(ProductSchema schema, List<TestCaseRecord> records) {
    }

    private String cellValue(ProductSchema schema, Map<String, Object> data, Map<String, String> managed,
            Column column, Map<String, String> computed) {
        if (column.managedKey() != null) {
            return managed.getOrDefault(column.managedKey(), "");
        }
        if (computed.containsKey(column.fieldKey())) {
            return computed.get(column.fieldKey());
        }
        Object value = data.get(column.fieldKey());
        return value == null ? "" : codec.displayValue(schema, column.fieldKey(), value);
    }

    // ------------------------------------------------------------ recalculation

    /**
     * Recalculates the workbook formulas of the batch behind the selection.
     *
     * <p>The baseline workbook of the batch is used as the base layer, so rows and helper columns
     * that were not imported still resolve, while the current case values are written back on top of
     * their cells. Results replace the stored values only for the cells that actually computed.
     */
    private Recalculated recalculate(String tenantId, ExportRequest request, Selection selection,
            List<ValidationIssue> issues) {
        if (!request.recalculateEnabled() || selection.records().isEmpty()) {
            return Recalculated.NONE;
        }
        if (selection.records().stream().noneMatch(record -> record.formulaCount() > 0)) {
            return Recalculated.NONE;
        }
        Set<String> batchIds = new LinkedHashSet<>();
        selection.records().forEach(record -> batchIds.add(record.batchId()));
        if (batchIds.size() != 1) {
            issues.add(ValidationIssue.warn("FORMULA_RECALC_SKIPPED",
                    "Selected cases come from " + batchIds.size() + " batches, formulas were not recalculated"));
            return Recalculated.NONE;
        }
        String batchId = batchIds.iterator().next();
        ImportBatchRow batch = batchRepository.find(tenantId, batchId).orElse(null);
        if (batch == null || !BaselineStore.isWorkbook(batch.sourceType())) {
            return Recalculated.NONE;
        }
        Path baseline = baselines.find(tenantId, batchId, batch.sourceSha256()).orElse(null);
        if (baseline == null) {
            issues.add(ValidationIssue.warn("FORMULA_RECALC_SKIPPED",
                    "The workbook baseline of this batch is missing, formulas were not recalculated"));
            return Recalculated.NONE;
        }

        List<WorkbookRecalculator.CaseTarget> targets = caseTargets(selection, batch);
        WorkbookRecalculator.Result result = WorkbookRecalculator.recalculate(baseline,
                batch.sheetName(), targets);
        result.warnings().stream().limit(20).forEach(message -> issues.add(
                ValidationIssue.warn("FORMULA_RECALC_WARNING", message)));
        if (result.failed() > 0) {
            issues.add(ValidationIssue.warn("FORMULA_RECALC_INCOMPLETE",
                    result.failed() + " formula cell(s) could not be recalculated and keep their stored value"));
        }
        if (result.evaluated() > 0) {
            log.info("Recalculated {} formula cell(s) for batch {}", result.evaluated(), batchId);
        }
        return new Recalculated(result.values());
    }

    /** Builds the write-back plan from the batch column mapping and the current case values. */
    private List<WorkbookRecalculator.CaseTarget> caseTargets(Selection selection, ImportBatchRow batch) {
        List<MappingEntry> mapping = mappingEntries(batch);
        List<WorkbookRecalculator.CaseTarget> targets = new ArrayList<>(selection.records().size());
        for (TestCaseRecord record : selection.records()) {
            if (record.sourceRow() == null) {
                continue;
            }
            Map<String, Object> data = json.toMap(record.dataJson());
            Map<String, String> managed = toStringMap(record);
            Map<String, String> formulas = codec.tradeFormulas(record.formulaJson());
            List<WorkbookRecalculator.CellTarget> cells = new ArrayList<>(mapping.size());
            for (MappingEntry entry : mapping) {
                String formula = entry.fieldKey == null ? null : formulas.get(entry.fieldKey);
                if (entry.fieldKey != null) {
                    FieldDef field = selection.schema().byKey().get(entry.fieldKey);
                    if (field == null) {
                        continue;
                    }
                    cells.add(new WorkbookRecalculator.CellTarget(entry.column, entry.fieldKey, field,
                            FieldCodec.canonical(field, data.get(entry.fieldKey)), formula));
                } else if (entry.managedKey != null) {
                    cells.add(new WorkbookRecalculator.CellTarget(entry.column, entry.managedKey, null,
                            managed.get(entry.managedKey), null));
                }
            }
            targets.add(new WorkbookRecalculator.CaseTarget(record.id(), record.sourceRow(), cells));
        }
        return targets;
    }

    private List<MappingEntry> mappingEntries(ImportBatchRow batch) {
        List<MappingEntry> entries = new ArrayList<>();
        if (batch.columnMappingJson() == null) {
            return entries;
        }
        for (Object item : json.toList(batch.columnMappingJson())) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String kind = String.valueOf(map.get("kind"));
            Object column = map.get("column");
            if (column == null || !(column instanceof Number number)) {
                continue;
            }
            String fieldKey = map.get("fieldKey") == null ? null : String.valueOf(map.get("fieldKey"));
            String managedKey = map.get("managedKey") == null ? null : String.valueOf(map.get("managedKey"));
            if ("TRADE".equals(kind) && fieldKey != null) {
                entries.add(new MappingEntry(number.intValue(), fieldKey, null));
            } else if ("MANAGED".equals(kind) && managedKey != null) {
                entries.add(new MappingEntry(number.intValue(), null, managedKey));
            }
        }
        return entries;
    }

    /** Recalculated values keyed by case id, then by field key. */
    private record Recalculated(Map<String, Map<String, String>> values) {

        static final Recalculated NONE = new Recalculated(Map.of());
    }

    private record MappingEntry(int column, String fieldKey, String managedKey) {
    }

    private record Column(String header, String fieldKey, String managedKey) {

        static Column trade(FieldDef field) {
            return new Column(field.header(), field.key(), null);
        }

        static Column managed(String header, String managedKey) {
            return new Column(header, null, managedKey);
        }
    }
}
