package ac.core.persistence;

import java.time.LocalDateTime;

/**
 * Persisted import task row.
 *
 * @param name user visible task name, unique per tenant
 * @param sourceSha256 SHA-256 of the source file content
 * @param sourceMd5    MD5 of the source file content, {@code null} for rows imported before it was
 *                     recorded
 * @param note free text remark shown on the task list, may be {@code null}
 */
public record ImportTaskRow(
        String id,
        String tenantId,
        String kind,
        String status,
        String name,
        String sourceName,
        String sourceSha256,
        String sourceMd5,
        int sheetCount,
        int batchCount,
        int totalRows,
        int invalidRows,
        String paramsJson,
        String issuesJson,
        String message,
        String note,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {
}
