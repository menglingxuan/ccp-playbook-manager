package ac.core.service.importflow;

import ac.core.common.ApiException;
import ac.core.config.AppProperties;
import ac.core.service.product.ProductCatalog;
import ac.core.service.validation.FieldRules;
import ac.domain.analysis.PreviewOptions;
import ac.domain.analysis.SampleRow;
import ac.domain.analysis.SheetAnalyzer;
import ac.domain.analysis.SheetDetector;
import ac.domain.analysis.SheetPreview;
import ac.domain.casefile.CaseRow;
import ac.domain.casefile.ConversionContext;
import ac.domain.casefile.IssueLevel;
import ac.domain.casefile.RowConverter;
import ac.domain.casefile.ValidationIssue;
import ac.domain.layout.SheetLayout;
import ac.domain.product.FieldDef;
import ac.domain.product.ProductSchema;
import ac.domain.table.CsvTableReader;
import ac.domain.table.ExcelTableReader;
import ac.domain.table.RawTable;
import ac.domain.table.SheetMeta;
import ac.domain.table.TableLimits;
import ac.domain.table.TableReadException;
import ac.domain.validation.ValidationOptions;
import ac.domain.validation.ValidationSeverity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Builds upload summaries and wizard previews. */
@Service
public class PreviewService {

    /** Extra rows kept beyond the sample size so layout detection still sees the data start. */
    private static final int PREVIEW_ROW_SLACK = 20;

    private final UploadStore uploadStore;
    private final SheetCache sheetCache;
    private final ProductCatalog products;
    private final ac.core.service.product.ProductGuesser guesser;
    private final FieldRules fieldRules;
    private final AppProperties properties;

    public PreviewService(UploadStore uploadStore, SheetCache sheetCache, ProductCatalog products,
            ac.core.service.product.ProductGuesser guesser, FieldRules fieldRules,
            AppProperties properties) {
        this.uploadStore = uploadStore;
        this.sheetCache = sheetCache;
        this.products = products;
        this.guesser = guesser;
        this.fieldRules = fieldRules;
        this.properties = properties;
    }

    public UploadView describe(String tenantId, String uploadId) {
        StoredUpload upload = uploadStore.require(tenantId, uploadId);
        return new UploadView(upload.uploadId(), upload.fileName(), upload.sourceType(), upload.sha256(),
                upload.sizeBytes(), listSheets(upload));
    }

    public PreviewResponse preview(String tenantId, String uploadId, PreviewRequest request) {
        StoredUpload upload = uploadStore.require(tenantId, uploadId);
        List<SheetMeta> sheets = listSheets(upload);
        int sheetIndex = request.resolvedSheetIndex();
        if (sheetIndex >= sheets.size()) {
            throw ApiException.invalid("Sheet index " + sheetIndex + " does not exist");
        }
        RawTable table = loadPreviewSheet(upload, sheetIndex);
        ProductSchema schema = resolveSchema(tenantId, request.productCode(), table);

        PreviewOptions options = new PreviewOptions(
                value(request.headerRow()),
                value(request.tradeStartColumn()),
                value(request.dataStartRow()),
                value(request.dataEndRow()));
        SheetPreview preview;
        try {
            preview = SheetAnalyzer.preview(schema, table, options, sampleLimit(request));
        } catch (TableReadException e) {
            throw ApiException.invalid(e.getMessage());
        }
        SheetPreview annotated = applyRules(tenantId, schema, preview);
        return new PreviewResponse(new UploadView(upload.uploadId(), upload.fileName(), upload.sourceType(),
                upload.sha256(), upload.sizeBytes(), sheets), sheetIndex, schema.code(), annotated);
    }

    /**
     * Adds an issue to every preview cell whose value fails a field rule.
     *
     * <p>The issue carries the field key, so the wizard marks the cell. A rule of severity
     * {@code ERROR} makes the row invalid, a {@code WARN} rule only flags the cell.
     */
    private SheetPreview applyRules(String tenantId, ProductSchema schema, SheetPreview preview) {
        FieldRules.Plan plan = fieldRules.plan(tenantId, schema.code(), schema);
        if (plan.empty() || preview.sampleRows().isEmpty()) {
            return preview;
        }
        List<SampleRow> rows = new ArrayList<>(preview.sampleRows().size());
        List<ValidationIssue> issues = new ArrayList<>(preview.issues());
        for (SampleRow row : preview.sampleRows()) {
            List<FieldRules.Check> checks =
                    fieldRules.check(plan, schema, row.texts(), ValidationOptions.SKIP_BLANK);
            List<ValidationIssue> rowIssues = new ArrayList<>(row.issues());
            for (FieldRules.Failure failure : fieldRules.failures(checks)) {
                String message = "Value '" + failure.value() + "' of '" + failure.header()
                        + "' does not match the rule " + String.join(", ", failure.failedRules())
                        + " (" + failure.expression() + ")";
                ValidationIssue issue = failure.severity() == ValidationSeverity.ERROR
                        ? ValidationIssue.error("VALIDATION_FAILED", message)
                        : ValidationIssue.warn("VALIDATION_FAILED", message);
                issue = issue.atRow(row.row()).forField(failure.fieldKey());
                rowIssues.add(issue);
                issues.add(issue);
            }
            boolean valid = rowIssues.stream().noneMatch(item -> item.level() == IssueLevel.ERROR);
            rows.add(new SampleRow(row.row(), row.values(), row.formulas(), row.managed(),
                    row.extras(), row.rawTexts(), rowIssues, valid));
        }
        return new SheetPreview(preview.productCode(), preview.valid(), issues, preview.layout(), rows,
                preview.stats());
    }

    /** Returns the first rows of a sheet with formula text, for troubleshooting. */
    public RawTable rawSheet(String tenantId, String uploadId, int sheetIndex, int rows) {
        StoredUpload upload = uploadStore.require(tenantId, uploadId);
        int limit = Math.max(1, Math.min(rows, 500));
        return loadSheet(upload, sheetIndex, limit);
    }

    public List<SheetMeta> listSheets(StoredUpload upload) {
        if ("CSV".equals(upload.sourceType())) {
            RawTable table = loadPreviewSheet(upload, 0);
            return List.of(new SheetMeta(0, table.sheetName(), table.declaredRowCount(),
                    table.columnCount(), false));
        }
        try {
            return ExcelTableReader.listSheets(upload.path(), properties.getLimits().getMaxSheets());
        } catch (TableReadException e) {
            throw ApiException.invalid(e.getMessage());
        }
    }

    private RawTable loadPreviewSheet(StoredUpload upload, int sheetIndex) {
        return loadSheet(upload, sheetIndex, previewKeepRows());
    }

    /**
     * One page of converted rows of a sheet.
     *
     * @param sheetIndex  resolved sheet index
     * @param productCode product used to convert the rows
     * @param offset      zero based offset inside the data rows
     * @param limit       requested page size
     * @param totalRows   number of data rows below the header
     * @param rows        converted rows of this page
     * @param issues      issues of this page, cell level ones carry their row number
     */
    public record PreviewPage(int sheetIndex, String productCode, int offset, int limit, int totalRows,
            List<SampleRow> rows, List<ValidationIssue> issues) {

        public PreviewPage {
            rows = List.copyOf(rows);
            issues = List.copyOf(issues);
        }

        /** True when another page follows this one. */
        public boolean hasMore() {
            return offset + rows.size() < totalRows;
        }
    }

    /**
     * Reads one page of data rows, so the wizard can browse a whole file without loading it.
     *
     * <p>The layout comes from the head window (cached), so paging never re-detects the header. The
     * page itself is read as a window: only those rows are converted, the total row count of the file
     * is reported so the pager knows where the end is.
     */
    public PreviewPage previewPage(String tenantId, String uploadId, PreviewRequest request) {
        StoredUpload upload = uploadStore.require(tenantId, uploadId);
        List<SheetMeta> sheets = listSheets(upload);
        int sheetIndex = request.resolvedSheetIndex();
        if (sheetIndex >= sheets.size()) {
            throw ApiException.invalid("Sheet index " + sheetIndex + " does not exist");
        }
        RawTable head = loadPreviewSheet(upload, sheetIndex);
        ProductSchema schema = resolveSchema(tenantId, request.productCode(), head);
        PreviewOptions options = new PreviewOptions(value(request.headerRow()),
                value(request.tradeStartColumn()), value(request.dataStartRow()), value(request.dataEndRow()));
        SheetAnalyzer.LayoutResolution resolution = SheetAnalyzer.resolveLayout(schema, head, options);
        if (resolution.layout().isEmpty()) {
            return new PreviewPage(sheetIndex, schema.code(), 0, 0, 0, List.of(), resolution.issues());
        }
        SheetLayout layout = resolution.layout().get();
        int dataStart = layout.dataStartRow();
        int totalRows = Math.max(0, head.declaredRowCount() - (dataStart - 1));
        if (options.dataEndRow() > 0) {
            totalRows = Math.min(totalRows, Math.max(0, options.dataEndRow() - dataStart + 1));
        }
        int requested = request.resolvedSampleLimit();
        int limit = requested > 0 ? requested : properties.getLimits().getPreviewSampleRows();
        int offset = request.resolvedSampleOffset();
        List<ValidationIssue> issues = new ArrayList<>(resolution.issues());
        if (offset >= totalRows) {
            return new PreviewPage(sheetIndex, schema.code(), offset, limit, totalRows, List.of(), issues);
        }
        RawTable page = readPage(upload, sheetIndex, dataStart - 1 + offset, limit);
        ConversionContext context = new ConversionContext(schema, layout, upload.fileName(),
                page.sheetName(), properties.getIds().getHashLength(),
                properties.getIds().isNormalizeFormulaRefs());
        List<SampleRow> rows = new ArrayList<>();
        for (int index = 0; index < page.rowCount(); index++) {
            // the page table only holds this window, so the row is looked up by its position inside
            // the window and then renumbered to the sheet row it came from
            int sheetRow = dataStart + offset + index;
            CaseRow row = RowConverter.convert(context, page, index + 1, sheetRow);
            SampleRow sample = new SampleRow(sheetRow, row.data(), row.formulas(), row.managed(),
                    row.extras(), row.rawTexts(), renumber(row.issues(), sheetRow), row.valid());
            rows.add(sample);
            issues.addAll(sample.issues());
        }
        List<SampleRow> checked = applyRulesToRows(tenantId, schema, rows, issues);
        return new PreviewPage(sheetIndex, schema.code(), offset, limit, totalRows, checked, issues);
    }

    /** Points the row issues of a converted page row at its sheet row. */
    private static List<ValidationIssue> renumber(List<ValidationIssue> issues, int sheetRow) {
        if (issues.isEmpty()) {
            return issues;
        }
        List<ValidationIssue> renumbered = new ArrayList<>(issues.size());
        for (ValidationIssue issue : issues) {
            renumbered.add(issue.row() == null ? issue : new ValidationIssue(issue.level(), issue.code(),
                    issue.message(), sheetRow, issue.column(), issue.header(), issue.fieldKey()));
        }
        return renumbered;
    }

    /** Reads a window of one sheet without caching it: pages must not evict the head analysis. */
    private RawTable readPage(StoredUpload upload, int sheetIndex, int skipRows, int limit) {        try {
            if ("CSV".equals(upload.sourceType())) {
                return CsvTableReader.readPage(upload.path(), skipRows, limit,
                        baseName(upload.fileName()), tableLimits());
            }
            return ExcelTableReader.readSheetPage(upload.path(), sheetIndex, skipRows, limit, tableLimits());
        } catch (TableReadException e) {
            throw ApiException.invalid(e.getMessage());
        }
    }

    /** Rows of one page for the raw (no product) preview. */
    public RawTable rawSheetPage(String tenantId, String uploadId, int sheetIndex, int offset, int rows) {
        StoredUpload upload = uploadStore.require(tenantId, uploadId);
        int limit = Math.max(1, Math.min(rows, PreviewRequest.MAX_SAMPLE_ROWS));
        return readPage(upload, sheetIndex, Math.max(offset, 0), limit);
    }

    /** Applies the field rules to an already converted page and marks the invalid rows. */
    private List<SampleRow> applyRulesToRows(String tenantId, ProductSchema schema, List<SampleRow> rows,
            List<ValidationIssue> issues) {
        FieldRules.Plan plan = fieldRules.plan(tenantId, schema.code(), schema);
        if (plan.empty() || rows.isEmpty()) {
            return rows;
        }
        List<SampleRow> checked = new ArrayList<>(rows.size());
        for (SampleRow row : rows) {
            List<ValidationIssue> rowIssues = new ArrayList<>(row.issues());
            for (FieldRules.Failure failure : fieldRules.failures(
                    fieldRules.check(plan, schema, row.texts(), ValidationOptions.SKIP_BLANK))) {
                String message = "Value '" + failure.value() + "' of '" + failure.header()
                        + "' does not match the rule " + String.join(", ", failure.failedRules())
                        + " (" + failure.expression() + ")";
                ValidationIssue issue = (failure.severity() == ValidationSeverity.ERROR
                        ? ValidationIssue.error("VALIDATION_FAILED", message)
                        : ValidationIssue.warn("VALIDATION_FAILED", message))
                        .atRow(row.row()).forField(failure.fieldKey());
                rowIssues.add(issue);
                issues.add(issue);
            }
            boolean valid = rowIssues.stream().noneMatch(item -> item.level() == IssueLevel.ERROR);
            checked.add(new SampleRow(row.row(), row.values(), row.formulas(), row.managed(),
                    row.extras(), row.rawTexts(), rowIssues, valid));
        }
        return checked;
    }

    /** Rows kept in memory for a preview; the total row count is still reported. */
    private int previewKeepRows() {
        return properties.getLimits().getPreviewRowLimit() + PREVIEW_ROW_SLACK;
    }

    private TableLimits tableLimits() {
        return new TableLimits(properties.getLimits().getMaxColumns(),
                properties.getLimits().getMaxCellLength());
    }

    private RawTable loadSheet(StoredUpload upload, int sheetIndex, int maxRows) {
        String key = upload.uploadId() + "#" + sheetIndex + "#" + maxRows;
        try {
            return sheetCache.get(key, () -> readSheet(upload, sheetIndex, maxRows));
        } catch (TableReadException e) {
            throw ApiException.invalid(e.getMessage());
        }
    }

    private RawTable readSheet(StoredUpload upload, int sheetIndex, int maxRows) {
        if ("CSV".equals(upload.sourceType())) {
            // a preview streams the file and keeps only the first rows, so a huge export never has to
            // fit into memory just to show its header
            return CsvTableReader.readPreview(upload.path(), Math.max(maxRows, 1),
                    baseName(upload.fileName()), tableLimits());
        }
        return ExcelTableReader.readSheetLimited(upload.path(), sheetIndex, Math.max(maxRows, 1),
                tableLimits());
    }

    /** Full sheet for the import commit; oversized sheets are rejected instead of truncated. */
    public RawTable loadFullSheet(String tenantId, StoredUpload upload, int sheetIndex) {
        int maxRows = properties.getLimits().getMaxRowsPerSheet();
        String key = upload.uploadId() + "#full#" + sheetIndex;
        try {
            return sheetCache.get(key, () -> "CSV".equals(upload.sourceType())
                    ? CsvTableReader.read(upload.path(), maxRows, baseName(upload.fileName()), tableLimits())
                    : ExcelTableReader.readSheet(upload.path(), sheetIndex, maxRows, tableLimits()));
        } catch (TableReadException e) {
            throw ApiException.invalid(e.getMessage());
        }
    }

    /** File name without its extension, used as the logical sheet name for CSV files. */
    private static String baseName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "CSV";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private ProductSchema resolveSchema(String tenantId, String requestedCode, RawTable table) {
        if (requestedCode != null && !requestedCode.isBlank()) {
            return products.requireImportable(tenantId, requestedCode);
        }
        return guesser.guess(tenantId, table);
    }

    private static int value(Integer number) {
        return number == null || number < 0 ? 0 : number;
    }

    /** Sample rows requested by the wizard, clamped to the configured default and the hard cap. */
    private int sampleLimit(PreviewRequest request) {
        int configured = properties.getLimits().getPreviewSampleRows();
        int requested = value(request.sampleLimit());
        if (requested <= 0) {
            return configured;
        }
        return Math.min(Math.max(requested, configured), PreviewRequest.MAX_SAMPLE_ROWS);
    }

    /** Exposes the detected header row for a given product, used by the wizard hints. */
    public int detectHeaderRow(ProductSchema schema, RawTable table) {
        return SheetDetector.detectHeaderRow(schema, table);
    }

    /** Field definition lookup used by the wizard to render type hints. */
    public List<FieldDef> fields(String tenantId, String productCode) {
        return products.require(tenantId, productCode).fields();
    }
}
