package ac.core.persistence;

import java.time.LocalDateTime;

/**
 * Persisted import task row.
 *
 * @param name user visible task name, unique per tenant
 */
public record ImportTaskRow(
        String id,
        String tenantId,
        String kind,
        String status,
        String name,
        String sourceName,
        String sourceSha256,
        int sheetCount,
        int batchCount,
        int totalRows,
        int invalidRows,
        String paramsJson,
        String issuesJson,
        String message,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {
}
