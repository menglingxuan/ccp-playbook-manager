package ac;

import ac.core.config.StartupModeResolver;
import ac.core.config.StartupPropertyOverrides;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication(scanBasePackages = "ac")
public class OtcTestDataApplication {

    public static void main(String[] args) {
        String configuredRoot = System.getProperty("app.data-root",
                System.getenv().getOrDefault("APP_DATA_ROOT", "./data"));
        String configuredTenant = System.getProperty("app.tenant.id", "default");

        StartupModeResolver.StartupResolution resolution =
                StartupModeResolver.resolve(args, configuredRoot, configuredTenant);
        Map<String, Object> overrides = StartupPropertyOverrides.of(resolution);

        // The resolution is applied as the first property source: the same keys exist in
        // application.yml, and default properties would lose against them.
        new SpringApplicationBuilder(OtcTestDataApplication.class)
                .initializers(context -> StartupPropertyOverrides.apply(context.getEnvironment(), overrides))
                .run(args);
    }

    /** Kept for tests that need a plain entry point. */
    public static void mainWithoutOverrides(String[] args) {
        SpringApplication.run(OtcTestDataApplication.class, args);
    }
}
