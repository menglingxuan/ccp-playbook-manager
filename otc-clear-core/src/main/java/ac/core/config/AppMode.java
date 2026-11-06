package ac.core.config;

/** Runtime mode of the application instance. */
public enum AppMode {
    /** One instance bound to a single tenant. */
    TENANT,
    /** Standalone single user instance; the tenant is fixed to {@code default}. */
    SINGLE
}
