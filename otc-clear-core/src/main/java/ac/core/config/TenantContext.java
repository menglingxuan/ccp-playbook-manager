package ac.core.config;

/**
 * Holds the tenant of the current request or job thread.
 *
 * <p>Async work must pass the tenant explicitly; never rely on this holder from a pooled thread.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(String tenantId) {
        CURRENT.set(tenantId);
    }

    /** Returns the current tenant; never {@code null}. */
    public static String requireTenant() {
        String tenant = CURRENT.get();
        if (tenant == null) {
            throw new IllegalStateException("Tenant context is not initialized");
        }
        return tenant;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
