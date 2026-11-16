package ac.core.config;

/**
 * Resolves the startup mode and tenant from command line arguments before the Spring context is
 * created, because the datasource URL and the data root depend on them.
 */
public final class StartupModeResolver {

    public static final String TENANT_ID_PATTERN = "[a-z0-9-]{1,32}";

    /** Startup switch that wipes the tenant data before the context starts. */
    public static final String INIT_DATABASE_FLAG = "--init-database";

    private StartupModeResolver() {
    }

    /**
     * Parses {@code --tenant --tenant-id=xxx}, {@code --no-tenant} and {@code --init-database}.
     *
     * <p>{@code --tenant-id xxx} is accepted as well, so a missing {@code =} does not silently drop
     * the tenant.
     *
     * @param args raw command line arguments
     * @return property overrides applied to the environment
     * @throws IllegalStateException when the arguments are contradictory or incomplete
     */
    public static StartupResolution resolve(String[] args, String configuredDataRoot, String configuredTenantId) {
        boolean tenantFlag = false;
        boolean singleFlag = false;
        boolean initDatabase = false;
        String tenantId = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--tenant".equals(arg)) {
                tenantFlag = true;
            } else if ("--no-tenant".equals(arg)) {
                singleFlag = true;
            } else if (INIT_DATABASE_FLAG.equals(arg)) {
                initDatabase = true;
            } else if (arg.startsWith("--tenant-id=")) {
                tenantId = arg.substring("--tenant-id=".length()).trim();
            } else if ("--tenant-id".equals(arg) && i + 1 < args.length) {
                tenantId = args[++i].trim();
            }
        }
        if (tenantFlag && singleFlag) {
            throw new IllegalStateException("--tenant and --no-tenant cannot be used together");
        }
        AppMode mode;
        String resolvedTenant;
        if (tenantFlag) {
            mode = AppMode.TENANT;
            if (tenantId == null || tenantId.isEmpty()) {
                throw new IllegalStateException("--tenant requires --tenant-id=<id> matching " + TENANT_ID_PATTERN);
            }
            resolvedTenant = tenantId;
        } else if (singleFlag) {
            mode = AppMode.SINGLE;
            resolvedTenant = "default";
        } else {
            mode = AppMode.SINGLE;
            resolvedTenant = configuredTenantId == null || configuredTenantId.isBlank()
                    ? "default" : configuredTenantId;
        }
        if (!resolvedTenant.matches(TENANT_ID_PATTERN)) {
            throw new IllegalStateException("Tenant id '" + resolvedTenant + "' does not match " + TENANT_ID_PATTERN);
        }
        return new StartupResolution(mode, resolvedTenant, normalizeRoot(configuredDataRoot), initDatabase);
    }

    private static String normalizeRoot(String dataRoot) {
        String root = dataRoot == null || dataRoot.isBlank() ? "./data" : dataRoot.trim();
        String normalized = root.replace('\\', '/');
        for (int index = 0; index < normalized.length(); index++) {
            char c = normalized.charAt(index);
            // the root ends up inside the H2 JDBC URL: ';' would append connection settings such as
            // INIT=RUNSCRIPT, quotes would break the URL, control characters hide the real value
            if (c == ';' || c == '\'' || c == '"' || Character.isISOControl(c)) {
                throw new IllegalStateException("The data root must not contain ';', quotes or control"
                        + " characters: " + normalized);
            }
        }
        return normalized;
    }

    /**
     * Result of the argument parsing.
     *
     * @param mode         resolved mode
     * @param tenantId     resolved tenant id
     * @param dataRoot     normalized data root
     * @param initDatabase true when {@code --init-database} asked to wipe the tenant data first
     */
    public record StartupResolution(AppMode mode, String tenantId, String dataRoot, boolean initDatabase) {

        public String tenantDirectory() {
            return dataRoot + "/tenants/" + tenantId;
        }

        public String databaseUrl() {
            return "jdbc:h2:file:" + tenantDirectory() + "/db/otc";
        }
    }
}
