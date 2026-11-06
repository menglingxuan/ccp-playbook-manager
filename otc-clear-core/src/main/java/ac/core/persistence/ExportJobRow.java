package ac.core.persistence;

import java.time.LocalDateTime;

/** Persisted export job row. */
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
        String createdBy,
        LocalDateTime createdAt) {
}
