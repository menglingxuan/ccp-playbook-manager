package ac.core.service.export;

import java.util.List;

/**
 * Result of an export run.
 *
 * @param exportId  export job id
 * @param fileName  produced file name
 * @param rowCount  number of written rows
 * @param sizeBytes file size
 * @param createdAt creation timestamp formatted for the UI
 * @param warnings  non blocking notes, for instance formulas that could not be recalculated
 */
public record ExportResult(String exportId, String fileName, int rowCount, long sizeBytes, String createdAt,
        List<String> warnings) {

    public ExportResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
