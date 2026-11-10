package ac.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

/** Startup resolution, and the precedence it must keep over configuration files. */
class StartupModeTest {

    @Test
    void tenantFlagsResolveTheTenant() {
        var resolution = StartupModeResolver.resolve(
                new String[] {"--tenant", "--tenant-id=hkex-otc", "--server.port=8080"}, "./data", "default");
        assertThat(resolution.mode()).isEqualTo(AppMode.TENANT);
        assertThat(resolution.tenantId()).isEqualTo("hkex-otc");
        assertThat(resolution.databaseUrl()).contains("/tenants/hkex-otc/db/otc");
    }

    @Test
    void tenantIdWithoutEqualsSignIsAccepted() {
        var resolution = StartupModeResolver.resolve(
                new String[] {"--tenant", "--tenant-id", "hkex-otc"}, "./data", "default");
        assertThat(resolution.mode()).isEqualTo(AppMode.TENANT);
        assertThat(resolution.tenantId()).isEqualTo("hkex-otc");
    }

    @Test
    void singleTenantFlagWins() {
        var resolution = StartupModeResolver.resolve(new String[] {"--no-tenant"}, "./data", "hkex-otc");
        assertThat(resolution.mode()).isEqualTo(AppMode.SINGLE);
        assertThat(resolution.tenantId()).isEqualTo("default");
    }

    @Test
    void conflictingOrIncompleteFlagsFail() {
        assertThatThrownBy(() -> StartupModeResolver.resolve(
                new String[] {"--tenant", "--no-tenant", "--tenant-id=a"}, "./data", "default"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be used together");
        assertThatThrownBy(() -> StartupModeResolver.resolve(new String[] {"--tenant"}, "./data", "default"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("--tenant-id");
        assertThatThrownBy(() -> StartupModeResolver.resolve(
                new String[] {"--tenant", "--tenant-id=Bad Id"}, "./data", "default"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");
    }

    /**
     * Regression: the mode and the tenant used to stay {@code SINGLE} / {@code default} because the
     * {@code application.yml} values won over Spring Boot's default properties.
     */
    @Test
    void overridesWinOverConfigurationFiles() {
        StandardEnvironment environment = new StandardEnvironment();
        // configuration file values are present, exactly as they were before the fix
        environment.getPropertySources().addLast(new MapPropertySource("configFile", Map.of(
                "app.mode", "SINGLE",
                "app.tenant.id", "default",
                "app.data-root", "./data")));

        var resolution = StartupModeResolver.resolve(
                new String[] {"--tenant", "--tenant-id=hkex-otc"}, "E:/data", "default");
        StartupPropertyOverrides.apply(environment, StartupPropertyOverrides.of(resolution));

        assertThat(environment.getProperty("app.mode")).isEqualTo("TENANT");
        assertThat(environment.getProperty("app.tenant.id")).isEqualTo("hkex-otc");
        assertThat(environment.getProperty("app.data-root")).isEqualTo("E:/data");
        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:h2:file:E:/data/tenants/hkex-otc/db/otc");
    }

    /** The computed keys must not be redeclared, otherwise a stale value can shadow the startup. */
    @Test
    void configurationFileDoesNotDeclareTheComputedKeys() {
        Map<String, Object> app = appSection();
        assertThat(app.keySet()).doesNotContain("mode", "data-root");
        assertThat(tenantSection()).doesNotContainKey("id");
    }

    @Test
    void configurationFileStillDeclaresTheDefaultsItOwns() {
        Map<String, Object> app = appSection();
        assertThat(app.get("version")).isNotNull();
        assertThat(tenantSection()).containsEntry("allow-header-override", false);
        assertThat(app.keySet()).contains("limits", "import", "export");
    }

    private static Map<String, Object> appSection() {
        Map<String, Object> app = loadSection(loadApplicationYaml(), "app");
        return app;
    }

    private static Map<String, Object> tenantSection() {
        return loadSection(appSection(), "tenant");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadSection(Map<String, Object> parent, String key) {
        Object section = parent.get(key);
        assertThat(section).as("application.yml must keep the " + key + " section").isNotNull();
        return (Map<String, Object>) section;
    }

    private static Map<String, Object> loadApplicationYaml() {
        try (var input = new ClassPathResource("application.yml").getInputStream()) {
            Map<String, Object> config = new Yaml().load(input);
            assertThat(config).as("application.yml must parse").isNotNull();
            return config;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read application.yml", e);
        }
    }
}
