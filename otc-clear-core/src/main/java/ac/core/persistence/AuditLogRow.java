package ac.core.persistence;

import java.time.LocalDateTime;

/** Persisted audit entry. */
public record AuditLogRow(
        String id,
        String tenantId,
        String entityType,
        String entityId,
        String action,
        String detail,
        String actor,
        LocalDateTime createdAt) {
}
