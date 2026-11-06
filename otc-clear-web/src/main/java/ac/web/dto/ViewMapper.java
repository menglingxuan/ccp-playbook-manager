package ac.web.dto;

import ac.core.common.TimeProvider;
import ac.core.persistence.ExportJobRow;
import ac.core.persistence.ExternalJobRow;
import ac.core.persistence.ExternalOutputRow;
import ac.core.persistence.ImportBatchRow;
import ac.core.persistence.ImportTaskRow;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Maps persisted rows to JSON friendly maps with preformatted timestamps. */
public final class ViewMapper {

    private ViewMapper() {
    }

    public static Map<String, Object> task(ImportTaskRow row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.id());
        view.put("kind", row.kind());
        view.put("status", row.status());
        view.put("sourceName", row.sourceName());
        view.put("sourceSha256", row.sourceSha256());
        view.put("sheetCount", row.sheetCount());
        view.put("batchCount", row.batchCount());
        view.put("totalRows", row.totalRows());
        view.put("invalidRows", row.invalidRows());
        view.put("message", row.message());
        view.put("createdAt", TimeProvider.display(row.createdAt()));
        view.put("finishedAt", TimeProvider.display(row.finishedAt()));
        view.put("durationSeconds", duration(row.startedAt(), row.finishedAt()));
        return view;
    }

    public static Map<String, Object> batch(ImportBatchRow row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.id());
        view.put("taskId", row.taskId());
        view.put("kind", row.kind());
        view.put("parentBatchId", row.parentBatchId());
        view.put("productCode", row.productCode());
        view.put("sourceFilename", row.sourceFilename());
        view.put("sheetName", row.sheetName());
        view.put("sourceType", row.sourceType());
        view.put("headerRow", row.headerRowIndex());
        view.put("tradeStartColumn", row.tradeStartCol());
        view.put("tradeEndColumn", row.tradeEndCol());
        view.put("schemaHash", row.schemaHash());
        view.put("status", row.status());
        view.put("totalRows", row.totalRows());
        view.put("invalidRows", row.invalidRows());
        view.put("note", row.note());
        view.put("createdAt", TimeProvider.display(row.createdAt()));
        return view;
    }

    public static Map<String, Object> exportJob(ExportJobRow row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.id());
        view.put("productCode", row.productCode());
        view.put("scopeType", row.scopeType());
        view.put("batchId", row.batchId());
        view.put("rowCount", row.rowCount());
        view.put("fileName", row.fileName());
        view.put("fileSize", row.fileSize());
        view.put("status", row.status());
        view.put("message", row.message());
        view.put("createdAt", TimeProvider.display(row.createdAt()));
        return view;
    }

    public static Map<String, Object> externalJob(ExternalJobRow row) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.id());
        view.put("productCode", row.productCode());
        view.put("caseCount", row.caseCount());
        view.put("status", row.status());
        view.put("requestId", row.requestId());
        view.put("externalJobId", row.externalJobId());
        view.put("folderPath", row.folderPath());
        view.put("inputCsvPath", row.inputCsvPath());
        view.put("message", row.message());
        view.put("dispatchPayload", row.dispatchPayload());
        view.put("lastResponse", row.lastResponse());
        view.put("createdAt", TimeProvider.display(row.createdAt()));
        view.put("dispatchedAt", TimeProvider.display(row.dispatchedAt()));
        view.put("finishedAt", TimeProvider.display(row.finishedAt()));
        view.put("lastPolledAt", TimeProvider.display(row.lastPolledAt()));
        return view;
    }

    public static Map<String, Object> output(ExternalOutputRow row, int caseCount) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", row.id());
        view.put("jobId", row.jobId());
        view.put("fileName", row.fileName());
        view.put("relativePath", row.relativePath());
        view.put("sizeBytes", row.sizeBytes());
        view.put("sha256", row.sha256());
        view.put("importedBatchId", row.importedBatchId());
        view.put("status", row.status());
        view.put("message", row.message());
        view.put("caseCount", caseCount);
        view.put("discoveredAt", TimeProvider.display(row.discoveredAt()));
        view.put("importedAt", TimeProvider.display(row.importedAt()));
        return view;
    }

    private static long duration(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) {
            return 0;
        }
        return java.time.Duration.between(from, to).getSeconds();
    }
}
