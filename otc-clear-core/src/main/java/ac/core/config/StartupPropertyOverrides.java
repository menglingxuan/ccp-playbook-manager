package ac.core.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Applies the startup resolution to the Spring environment.
 *
 * <p>The mode, the tenant and the data root are decided before the context starts, so their values
 * must win over every other source (including {@code application.yml}). {@code SpringApplication}'s
 * default properties sit at the lowest precedence, which is why the overrides are registered as the
 * first property source instead.
 */
public final class StartupPropertyOverrides {

    /** Name of the property source carrying the startup resolution. */
    public static final String SOURCE_NAME = "startupResolution";

    private StartupPropertyOverrides() {
    }

    /**
     * Builds the overrides of one startup resolution.
     *
     * @param resolution resolved mode, tenant and data root
     * @return property values that must not be shadowed by configuration files
     */
    public static Map<String, Object> of(StartupModeResolver.StartupResolution resolution) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("app.mode", resolution.mode().name());
        overrides.put("app.tenant.id", resolution.tenantId());
        overrides.put("app.data-root", resolution.dataRoot());
        // the database follows the tenant directory, so it is as authoritative as the tenant itself
        overrides.put("spring.datasource.url", resolution.databaseUrl());
        overrides.put("spring.flyway.url", resolution.databaseUrl());
        return overrides;
    }

    /** Registers the overrides as the highest priority property source of the environment. */
    public static void apply(ConfigurableEnvironment environment, Map<String, Object> overrides) {
        environment.getPropertySources().remove(SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, overrides));
    }
}
