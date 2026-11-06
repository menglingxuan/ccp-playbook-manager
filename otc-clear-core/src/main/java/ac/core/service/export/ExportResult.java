package ac.core.service.export;

/**
 * Result of an export run.
 *
 * @param exportId  export job id
 * @param fileName  produced file name
 * @param rowCount  number of written rows
 * @param sizeBytes file size
 * @param createdAt creation timestamp formatted for the UI
 */
public record ExportResult(String exportId, String fileName, int rowCount, long sizeBytes, String createdAt) {
}
