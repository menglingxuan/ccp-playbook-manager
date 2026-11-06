package ac.core.service.importflow;

/** Result of importing one sheet. */
public record BatchImportResult(
        String batchId,
        int sheetIndex,
        String sheetName,
        String productCode,
        int totalRows,
        int invalidRows,
        int emptyRows,
        String status) {
}
