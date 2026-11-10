package ac.core.persistence;

import java.time.LocalDateTime;

/** Persisted import batch row. */
public record ImportBatchRow(
        String id,
        String tenantId,
        String taskId,
        String uploadId,
        String kind,
        String parentBatchId,
        String productCode,
        String sourceFilename,
        String sourceSha256,
        String sourceType,
        String sheetName,
        int headerRowIndex,
        int tradeStartCol,
        int tradeEndCol,
        String layoutJson,
        String columnMappingJson,
        String schemaHash,
        String status,
        int totalRows,
        int invalidRows,
        String note,
        boolean favorite,
        boolean pinned,
        String tags,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt) {

    /** Tags of the console, without the matching sentinel characters. */
    public java.util.List<String> tagList() {
        if (tags == null || tags.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .toList();
    }
}
