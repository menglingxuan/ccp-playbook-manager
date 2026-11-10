package ac.web.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Runtime context for the UI shell.
 *
 * @param mode           TENANT or SINGLE
 * @param tenantId       bound tenant
 * @param dataRoot       storage root
 * @param version        application version
 * @param database       database product and version
 * @param profiles       active spring profiles
 * @param auditEnabled   whether the audit entry is available
 * @param limits         capability limits shown in the UI
 * @param exportDefaults default CSV options
 * @param products       available products
 */
public record ContextView(
        String mode,
        String tenantId,
        String dataRoot,
        String version,
        String database,
        List<String> profiles,
        boolean auditEnabled,
        LimitsView limits,
        Map<String, Object> exportDefaults,
        List<ProductView> products) {

    /**
     * Limits exposed to the UI.
     *
     * @param maxFileSizeBytes maximum upload size
     * @param maxCloneCount    copies per clone run
     * @param maxBulkDelete    cases per bulk delete
     * @param maxRowsPerSheet  rows per sheet
     */
    public record LimitsView(long maxFileSizeBytes, int maxCloneCount, int maxBulkDelete, int maxRowsPerSheet) {
    }

    /** Audit entry view. */
    public record AuditView(String id, String entityType, String entityId, String action, String detail,
            String actor, LocalDateTime createdAt, String createdAtText) {
    }
}
