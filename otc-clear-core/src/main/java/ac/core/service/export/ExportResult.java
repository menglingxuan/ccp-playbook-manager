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
 * @param overrides transient field overrides that shaped this export, empty when none was used
 */
public record ExportResult(String exportId, String fileName, int rowCount, long sizeBytes, String createdAt,
        List<String> warnings, List<ExportOverrideView> overrides) {

    public ExportResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        overrides = overrides == null ? List.of() : List.copyOf(overrides);
    }

    public ExportResult(String exportId, String fileName, int rowCount, long sizeBytes, String createdAt,
            List<String> warnings) {
        this(exportId, fileName, rowCount, sizeBytes, createdAt, warnings, List.of());
    }

    /**
     * One transient override recorded on an export.
     *
     * @param fieldKey field that was overridden
     * @param header   header shown in the UI
     * @param mode     {@code VALUE}, {@code FORMULA} or {@code CLEAR}
     * @param display  new value or formula as shown to the user; empty for a cleared field
     */
    public record ExportOverrideView(String fieldKey, String header, String mode, String display) {
    }
}
