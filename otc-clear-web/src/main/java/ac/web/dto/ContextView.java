package ac.web.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Runtime context for the UI shell.
 *
 * @param mode           TENANT or SINGLE
 * @param tenantId       bound tenant
 * @param showTenant     whether the tenant identifier may be shown in the shell
 * @param dataRoot       storage root
 * @param version        application version
 * @param database       database product and version
 * @param profiles       active spring profiles
 * @param auditEnabled   whether the audit entry is available
 * @param limits         capability limits shown in the UI
 * @param ui             shell display preferences
 * @param exportDefaults default CSV options
 * @param products       available products
 */
public record ContextView(
        String mode,
        String tenantId,
        boolean showTenant,
        String dataRoot,
        String version,
        String database,
        List<String> profiles,
        boolean auditEnabled,
        LimitsView limits,
        UiPreferences ui,
        Map<String, Object> exportDefaults,
        List<ProductView> products) {

    /**
     * Shell display preferences.
     *
     * @param issueInlineLimit issues listed in place before a paginated panel opens
     * @param issuePreviewRows issues rendered before the "more" row
     */
    public record UiPreferences(int issueInlineLimit, int issuePreviewRows) {
    }

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
