package ac.core.service.importflow;

import ac.core.common.ApiException;
import ac.core.config.AppProperties;
import ac.core.service.product.ProductCatalog;
import ac.domain.analysis.PreviewOptions;
import ac.domain.analysis.SheetAnalyzer;
import ac.domain.analysis.SheetDetector;
import ac.domain.analysis.SheetPreview;
import ac.domain.product.FieldDef;
import ac.domain.product.ProductSchema;
import ac.domain.table.CsvTableReader;
import ac.domain.table.ExcelTableReader;
import ac.domain.table.RawTable;
import ac.domain.table.SheetMeta;
import ac.domain.table.TableReadException;
import java.util.List;
import org.springframework.stereotype.Service;

/** Builds upload summaries and wizard previews. */
@Service
public class PreviewService {

    private final UploadStore uploadStore;
    private final SheetCache sheetCache;
    private final ProductCatalog products;
    private final ac.core.service.product.ProductGuesser guesser;
    private final AppProperties properties;

    public PreviewService(UploadStore uploadStore, SheetCache sheetCache, ProductCatalog products,
            ac.core.service.product.ProductGuesser guesser, AppProperties properties) {
        this.uploadStore = uploadStore;
        this.sheetCache = sheetCache;
        this.products = products;
        this.guesser = guesser;
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
        return new PreviewResponse(new UploadView(upload.uploadId(), upload.fileName(), upload.sourceType(),
                upload.sha256(), upload.sizeBytes(), sheets), sheetIndex, schema.code(), preview);
    }

    /** Returns the first rows of a sheet with formula text, for troubleshooting. */
    public RawTable rawSheet(String tenantId, String uploadId, int sheetIndex, int rows) {
        StoredUpload upload = uploadStore.require(tenantId, uploadId);
        int limit = Math.max(1, Math.min(rows, 500));
        return loadSheet(upload, sheetIndex, limit);
    }

    public RawTable loadFullSheet(String tenantId, StoredUpload upload, int sheetIndex) {
        return loadSheet(upload, sheetIndex, properties.getLimits().getMaxRowsPerSheet());
    }

    public List<SheetMeta> listSheets(StoredUpload upload) {
        if ("CSV".equals(upload.sourceType())) {
            RawTable table = loadPreviewSheet(upload, 0);
            return List.of(new SheetMeta(0, table.sheetName(), table.rowCount(), table.columnCount(), false));
        }
        try {
            return ExcelTableReader.listSheets(upload.path(), properties.getLimits().getMaxSheets());
        } catch (TableReadException e) {
            throw ApiException.invalid(e.getMessage());
        }
    }

    private RawTable loadPreviewSheet(StoredUpload upload, int sheetIndex) {
        return loadSheet(upload, sheetIndex, properties.getLimits().getPreviewRowLimit());
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
            return CsvTableReader.read(upload.path(), properties.getLimits().getMaxRowsPerSheet(),
                    baseName(upload.fileName()));
        }
        return ExcelTableReader.readSheet(upload.path(), sheetIndex, Math.max(maxRows, 1));
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
