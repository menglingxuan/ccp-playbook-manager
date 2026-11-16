package ac.core.persistence;

import java.time.LocalDateTime;

/**
 * Persisted export job row.
 *
 * @param note free text remark shown on the export list, may be {@code null}
 */
public record ExportJobRow(
        String id,
        String tenantId,
        String productCode,
        String scopeType,
        String batchId,
        int rowCount,
        String fileName,
        String storagePath,
        long fileSize,
        String optionsJson,
        String status,
        String message,
        String note,
        String createdBy,
        LocalDateTime createdAt) {
}
