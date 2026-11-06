package ac.core.persistence;

import java.time.LocalDateTime;

/** Persisted external output file row. */
public record ExternalOutputRow(
        String id,
        String tenantId,
        String jobId,
        String fileName,
        String relativePath,
        long sizeBytes,
        String sha256,
        String importedBatchId,
        String status,
        String message,
        LocalDateTime discoveredAt,
        LocalDateTime importedAt) {
}
