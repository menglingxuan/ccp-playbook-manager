package ac;

import ac.core.config.StartupModeResolver;
import java.util.LinkedHashMap;
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

        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("app.mode", resolution.mode().name());
        overrides.put("app.tenant.id", resolution.tenantId());
        overrides.put("app.data-root", resolution.dataRoot());
        overrides.put("spring.datasource.url", resolution.databaseUrl());
        overrides.put("spring.flyway.url", resolution.databaseUrl());

        new SpringApplicationBuilder(OtcTestDataApplication.class)
                .properties(overrides)
                .run(args);
    }

    /** Kept for tests that need a plain entry point. */
    public static void mainWithoutOverrides(String[] args) {
        SpringApplication.run(OtcTestDataApplication.class, args);
    }
}
