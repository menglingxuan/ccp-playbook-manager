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
import ac.domain.casefile.CellTexts;
import ac.core.service.settings.SettingsService;
import ac.domain.casefile.ValidationIssue;
import ac.domain.export.CsvFormatOptions;
import ac.domain.export.CsvWriter;
import ac.domain.export.ManagedColumnScope;
import ac.domain.product.FieldCodec;
import ac.domain.product.FieldDef;
import ac.domain.product.ProductSchema;
import ac.domain.table.CsvTableReader;
import ac.domain.table.RawRow;
import ac.domain.table.RawTable;
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
    /** Upper bound of the rows returned by the preview endpoint. */
    private static final int PREVIEW_MAX_ROWS = 500;

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

    /** Everything a request resolves once: options, output columns, overrides and the batch header. */
    private record ResolvedCase(CsvFormatOptions options, List<Column> columns, Overlay overlay,
            Recalculated recalculated, ImportBatchRow batch) {
    }

    public ExportPrecheck precheck(String tenantId, ExportRequest request) {
        List<ValidationIssue> issues = new ArrayList<>();
        return precheck(tenantId, request, issues);
    }

    /** Precheck that also reports the recalculation warnings it produced. */
    private ExportPrecheck precheck(String tenantId, ExportRequest request, List<ValidationIssue> issues) {
        Selection selection = select(tenantId, request);
        return precheck(request, selection, resolve(tenantId, request, selection, issues), issues);
    }

    /**
     * Resolves the request once: settings, columns, overrides, recalculation and the batch row.
     *
     * <p>The batch is looked up once instead of once per exported row, and the tenant export options
     * are read once instead of once per helper.
     */
    private ResolvedCase resolve(String tenantId, ExportRequest request, Selection selection,
            List<ValidationIssue> issues) {
        CsvFormatOptions options = options(tenantId, request);
        ImportBatchRow batch = request.batchId() == null ? null
                : batchRepository.find(tenantId, request.batchId()).orElse(null);
        List<Column> columns = columns(request, selection, options, batch);
        Overlay overlay = overlay(tenantId, request, selection);
        Recalculated recalculated = recalculate(tenantId, request, selection, overlay, issues);
        return new ResolvedCase(options, columns, overlay, recalculated, batch);
    }

    private ExportPrecheck precheck(ExportRequest request, Selection selection, ResolvedCase resolved,
            List<ValidationIssue> issues) {
        Set<String> keys = new LinkedHashSet<>();
        for (TestCaseRecord record : selection.records()) {
            Values values = values(selection.schema(), record, resolved.overlay());
            for (FieldDef required : selection.schema().requiredFields()) {
                String value = values.texts().trade(required.key());
                if (value == null || value.isBlank()) {
                    issues.add(ValidationIssue.error("REQUIRED_VALUE_MISSING",
                                    "Required field '" + required.header() + "' is empty")
                            .atRow(record.rowNo()).forField(required.key()));
                }
            }
            String keyValue = keyValue(selection.schema(), values.texts());
            if (keyValue != null && !keyValue.isBlank() && !keys.add(keyValue)) {
                issues.add(ValidationIssue.error("KEY_DUPLICATE",
                                "Trade id '" + keyValue + "' appears more than once")
                        .atRow(record.rowNo()));
            }
        }
        ImportBatchRow batch = resolved.batch();
        if (batch != null && batch.schemaHash() != null
                && !batch.schemaHash().equals(selection.schema().schemaHash())) {
            issues.add(ValidationIssue.warn("SCHEMA_HASH_MISMATCH",
                    "The product definition changed since this batch was imported"));
        }

        List<Column> columns = resolved.columns();
        List<String> headers = columns.stream().map(Column::header).toList();
        TestCaseRecord first = selection.records().isEmpty() ? null : selection.records().get(0);
        List<String> sample = List.of();
        if (first != null) {
            Values values = values(selection.schema(), first, resolved.overlay());
            Map<String, String> computed = resolved.recalculated().values().getOrDefault(first.id(), Map.of());
            sample = columns.stream()
                    .map(column -> cellValue(selection.schema(), values.texts(), column, computed))
                    .toList();
        }
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
        List<ValidationIssue> precheckIssues = new ArrayList<>();
        ExportRequest storedRequest = new ExportRequest(request.batchId(), request.caseIds(),
                request.options(), Boolean.FALSE, request.overrides());
        ExportPrecheck precheck = precheck(storedRequest, selection,
                resolve(tenantId, storedRequest, selection, precheckIssues), precheckIssues);
        if (!precheck.valid()) {
            throw new ApiException(ac.core.common.ErrorCode.INVALID_REQUEST,
                    "Export precheck failed", Map.of("issues", precheck.issues()));
        }

        List<ValidationIssue> issues = new ArrayList<>();
        ResolvedCase resolved = resolve(tenantId, request, selection, issues);
        // a formula that cannot be recomputed blocks the run; the precheck above only sees the
        // stored values, so the recalculation errors are checked here, before anything is written
        List<ValidationIssue> recalculationErrors = issues.stream()
                .filter(issue -> issue.level() == ac.domain.casefile.IssueLevel.ERROR)
                .toList();
        if (!recalculationErrors.isEmpty()) {
            throw new ApiException(ac.core.common.ErrorCode.INVALID_REQUEST,
                    "Formulas could not be recalculated",
                    Map.of("issues", recalculationErrors,
                            "hint", "Turn formula recalculation off to export the stored values"));
        }
        CsvContent content = content(selection, resolved);
        List<String> headers = content.headers();
        List<List<String>> rows = content.rows();
        List<ExportResult.ExportOverrideView> overrides = overrideViews(resolved.overlay());
        CsvFormatOptions options = resolved.options();

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
            deleteQuietly(temp);
            log.warn("Cannot write export file {}", target, e);
            throw new ApiException(ac.core.common.ErrorCode.INTERNAL_ERROR,
                    "The export file could not be written, check the free space of the data directory");
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(temp);
            log.warn("Cannot finalize export file {}", target, e);
            throw new ApiException(ac.core.common.ErrorCode.INTERNAL_ERROR,
                    "The export file could not be finalized, check the free space of the data directory");
        }

        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            size = 0;
        }
        String exportId = Ids.newId();
        String scopeBatchId = request.batchId() != null ? request.batchId() : selectionBatchId(selection);
        String relativePath = dataLayout.tenantRoot(tenantId).relativize(target).toString().replace('\\', '/');
        Map<String, Object> storedOptions = new LinkedHashMap<>();
        storedOptions.put("delimiter", String.valueOf(options.delimiter()));
        storedOptions.put("quoteAll", options.quoteAll());
        storedOptions.put("managedColumns", options.managedColumns().name());
        storedOptions.put("bom", options.bom());
        storedOptions.put("lineEnding", options.lineEnding().equals("\n") ? "LF" : "CRLF");
        if (!overrides.isEmpty()) {
            storedOptions.put("overrides", overrides);
        }
        ExportJobRow job = new ExportJobRow(exportId, tenantId, selection.schema().code(),
                request.batchId() != null ? "BATCH" : "SELECTION", scopeBatchId, rows.size(), fileName,
                relativePath, size, json.toJson(storedOptions),
                "COMPLETED", null, null, "system", time.nowUtc());
        exportRepository.insert(job);

        return new ExportResult(exportId, fileName, rows.size(), size, TimeProvider.display(job.createdAt()),
                issues.stream().map(ValidationIssue::message).toList(), overrides);
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

    /** Replaces the remark of one export record; an empty text clears it. */
    @Transactional
    public String updateNote(String tenantId, String exportId, String rawNote) {
        requireJob(tenantId, exportId);
        String note = ac.core.common.Notes.normalize(rawNote);
        exportRepository.updateNote(tenantId, exportId, note);
        return note;
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

    /**
     * Preview of the stored CSV: the header row plus the first data rows.
     *
     * <p>The file is parsed with the delimiter and quoting it was written with, so the preview always
     * matches the downloadable file. Large exports are truncated instead of being rejected.
     *
     * @param limit maximum number of data rows to return, capped at {@value #PREVIEW_MAX_ROWS}
     */
    public ExportPreview preview(String tenantId, String exportId, int limit) {
        ExportJobRow job = requireJob(tenantId, exportId);
        Path path = requireFile(tenantId, exportId);
        int wanted = Math.max(1, Math.min(limit, PREVIEW_MAX_ROWS));
        RawTable table;
        try {
            table = CsvTableReader.readLimited(path, wanted + 2);
        } catch (RuntimeException e) {
            throw new ApiException(ac.core.common.ErrorCode.INTERNAL_ERROR,
                    "Cannot read the export file: " + e.getMessage());
        }
        List<String> headers = table.rowCount() == 0 ? List.of() : table.headerTexts(0);
        int available = Math.max(0, table.rowCount() - 1);
        int shown = Math.min(available, wanted);
        List<List<String>> rows = new ArrayList<>(shown);
        for (int index = 0; index < shown; index++) {
            RawRow row = table.row(index + 1);
            List<String> cells = new ArrayList<>(headers.size());
            for (int column = 0; column < headers.size(); column++) {
                cells.add(row.text(column));
            }
            rows.add(cells);
        }
        return new ExportPreview(exportId, job.fileName(), job.rowCount(), shown, job.rowCount() > shown,
                headers, rows);
    }

    /** Preview payload of one export record. */
    public record ExportPreview(String exportId, String fileName, int rowCount, int shownRows,
            boolean truncated, List<String> headers, List<List<String>> rows) {

        public ExportPreview {
            headers = List.copyOf(headers);
            rows = rows.stream().map(cells -> List.copyOf(cells)).toList();
        }
    }

    private CsvFormatOptions options(String tenantId, ExportRequest request) {
        CsvFormatOptions defaults = settings.exportOptions(tenantId);
        return request.options() == null ? defaults : request.options().merge(defaults);
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
     */
    public CsvContent content(String tenantId, ExportRequest request, List<ValidationIssue> issues) {
        Selection selection = select(tenantId, request);
        return content(selection, resolve(tenantId, request, selection, issues));
    }

    /** Rendered CSV content of an already resolved request. */
    private CsvContent content(Selection selection, ResolvedCase resolved) {
        List<Column> columns = resolved.columns();
        List<String> headers = columns.stream().map(Column::header).toList();
        List<List<String>> rows = new ArrayList<>(selection.records().size());
        for (TestCaseRecord record : selection.records()) {
            Values values = values(selection.schema(), record, resolved.overlay());
            Map<String, String> computed =
                    resolved.recalculated().values().getOrDefault(record.id(), Map.of());
            List<String> row = new ArrayList<>(columns.size());
            for (Column column : columns) {
                row.add(cellValue(selection.schema(), values.texts(), column, computed));
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

    /**
     * Batch behind a case selection, {@code null} when the selected cases span several batches.
     *
     * <p>Kept on the record so the export list can link back to the batch it came from.
     */
    private String selectionBatchId(Selection selection) {
        Set<String> batchIds = new LinkedHashSet<>();
        selection.records().forEach(record -> batchIds.add(record.batchId()));
        return batchIds.size() == 1 ? batchIds.iterator().next() : null;
    }

    private List<Column> columns(ExportRequest request, Selection selection, CsvFormatOptions options,
            ImportBatchRow batch) {
        ManagedColumnScope scope = options.managedColumns();
        List<Column> columns = new ArrayList<>();
        if (scope == ManagedColumnScope.ALL) {
            appendSourceOrder(columns, batch, selection);
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

    private void appendSourceOrder(List<Column> columns, ImportBatchRow batch, Selection selection) {
        Map<String, Column> managed = new LinkedHashMap<>();
        Set<String> emitted = new LinkedHashSet<>();
        if (batch != null) {
            for (Object item : json.toList(
                    batch.columnMappingJson() == null ? "[]" : batch.columnMappingJson())) {
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

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("Cannot delete temporary export file {}", file, e);
        }
    }

    private record Selection(ProductSchema schema, List<TestCaseRecord> records) {
    }

    private String cellValue(ProductSchema schema, CellTexts texts, Column column,
            Map<String, String> computed) {
        if (column.managedKey() != null) {
            return nullToEmpty(texts.managedValue(column.managedKey()));
        }
        if (computed.containsKey(column.fieldKey())) {
            return computed.get(column.fieldKey());
        }
        return nullToEmpty(texts.trade(column.fieldKey()));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // ---------------------------------------------------------- transient overrides

    /**
     * Resolves the transient overrides of one request against the product of the selection.
     *
     * <p>The same rules as a bulk update apply, plus one export specific guard: overriding the unique
     * key of more than one exported row would produce duplicate trade ids, which is refused up front.
     */
    private Overlay overlay(String tenantId, ExportRequest request, Selection selection) {
        if (!request.hasOverrides()) {
            return Overlay.NONE;
        }
        Map<String, CaseService.ResolvedOverride> byField = new LinkedHashMap<>();
        for (ac.core.service.cases.FieldOverride override : request.overrides()) {
            CaseService.ResolvedOverride resolved = caseService.resolveOverride(tenantId,
                    selection.schema(), override);
            byField.put(resolved.fieldKey(), resolved);
        }
        FieldDef uniqueField = selection.schema().uniqueField();
        if (uniqueField != null && byField.containsKey(uniqueField.key()) && selection.records().size() > 1) {
            CaseService.ResolvedOverride resolved = byField.get(uniqueField.key());
            if (resolved.mode() == ac.core.service.cases.BulkFieldUpdateRequest.Mode.FORMULA) {
                throw new ApiException(ac.core.common.ErrorCode.CONFLICT,
                        "The key field '" + uniqueField.header() + "' cannot hold a formula");
            }
            throw new ApiException(ac.core.common.ErrorCode.CONFLICT,
                    "Overriding the key field '" + uniqueField.header() + "' on "
                            + selection.records().size() + " exported cases would create duplicate keys");
        }
        return new Overlay(List.copyOf(byField.values()));
    }

    /** Case values with the transient overrides applied; nothing here touches the database. */
    private Values values(ProductSchema schema, TestCaseRecord record, Overlay overlay) {
        Map<String, Object> data = new LinkedHashMap<>(json.toMap(record.dataJson()));
        Map<String, String> managed = toStringMap(record);
        Map<String, String> formulas = new LinkedHashMap<>(codec.tradeFormulas(record.formulaJson()));
        formulas.putAll(codec.managedFormulas(record.formulaJson()));
        CellTexts stored = codec.texts(record);
        Map<String, String> rawData = new LinkedHashMap<>(stored.data());
        Map<String, String> rawManaged = new LinkedHashMap<>(stored.managed());
        for (CaseService.ResolvedOverride resolved : overlay.items()) {
            caseService.applyOverride(resolved, schema, data, managed, formulas, rawData, rawManaged);
        }
        return new Values(data, managed, formulas, CellTexts.of(data, managed,
                new CellTexts(rawData, rawManaged)));
    }

    private List<ExportResult.ExportOverrideView> overrideViews(Overlay overlay) {
        return overlay.items().stream()
                .map(resolved -> new ExportResult.ExportOverrideView(resolved.fieldKey(),
                        resolved.field().header(), resolved.mode().name(),
                        caseService.overrideDisplay(resolved)))
                .toList();
    }

    private String keyValue(ProductSchema schema, CellTexts texts) {
        FieldDef uniqueField = schema.uniqueField();
        if (uniqueField == null) {
            return null;
        }
        String text = texts.trade(uniqueField.key());
        return text == null || text.isBlank() ? null : FieldCodec.canonical(uniqueField, text);
    }

    /** Resolved overrides of one request, in the order the caller provided them. */
    private record Overlay(List<CaseService.ResolvedOverride> items) {

        static final Overlay NONE = new Overlay(List.of());
    }

    /** In-memory case values after the overrides were applied. */
    private record Values(Map<String, Object> data, Map<String, String> managed,
            Map<String, String> formulas, CellTexts texts) {
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
            Overlay overlay, List<ValidationIssue> issues) {
        if (!request.recalculateEnabled() || selection.records().isEmpty()) {
            return Recalculated.NONE;
        }
        // an overridden formula must be recalculated even when no stored formula is left on the row
        boolean overrideFormulas = overlay.items().stream().anyMatch(
                resolved -> resolved.mode() == ac.core.service.cases.BulkFieldUpdateRequest.Mode.FORMULA);
        if (!overrideFormulas
                && selection.records().stream().noneMatch(record -> record.formulaCount() > 0)) {
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

        List<WorkbookRecalculator.CaseTarget> targets = caseTargets(selection, batch, overlay);
        WorkbookRecalculator.Result result = WorkbookRecalculator.recalculate(baseline,
                batch.sheetName(), targets);
        // a formula that cannot be recomputed must not silently fall back to the value cached by the
        // source file: the export is refused instead, and the caller can switch recalculation off to
        // export the stored values on purpose
        result.warnings().stream().limit(20).forEach(message -> issues.add(
                ValidationIssue.error("FORMULA_RECALC_FAILED", message)));
        if (result.failed() > 0) {
            log.info("{} formula cell(s) of batch {} could not be recalculated", result.failed(), batchId);
        }
        if (result.evaluated() > 0) {
            log.info("Recalculated {} formula cell(s) for batch {}", result.evaluated(), batchId);
        }
        return new Recalculated(result.values());
    }

    /** Builds the write-back plan from the batch column mapping and the current case values. */
    private List<WorkbookRecalculator.CaseTarget> caseTargets(Selection selection, ImportBatchRow batch,
            Overlay overlay) {
        List<MappingEntry> mapping = mappingEntries(batch);
        List<WorkbookRecalculator.CaseTarget> targets = new ArrayList<>(selection.records().size());
        for (TestCaseRecord record : selection.records()) {
            if (record.sourceRow() == null) {
                continue;
            }
            Values values = values(selection.schema(), record, overlay);
            Map<String, Object> data = values.data();
            Map<String, String> managed = values.managed();
            Map<String, String> formulas = values.formulas();
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
