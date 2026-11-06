package ac.core.persistence;

import java.time.LocalDateTime;

/** Persisted external batch import job row. */
public record ExternalJobRow(
        String id,
        String tenantId,
        String productCode,
        int caseCount,
        String folderPath,
        String inputCsvPath,
        String requestId,
        String externalJobId,
        String status,
        String dispatchPayload,
        String lastResponse,
        String message,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime dispatchedAt,
        LocalDateTime finishedAt,
        LocalDateTime lastPolledAt) {
}
