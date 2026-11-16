package ac;

import ac.core.config.DataRootResolver;
import ac.core.config.DatabaseInitializer;
import ac.core.config.StartupHelp;
import ac.core.config.StartupModeResolver;
import ac.core.config.StartupPropertyOverrides;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication(scanBasePackages = "ac")
public class OtcTestDataApplication {

    public static void main(String[] args) {
        if (StartupHelp.requested(args)) {
            System.out.println(StartupHelp.text());
            return;
        }

        String rawRoot = System.getProperty("app.data-root");
        if (rawRoot == null || rawRoot.isBlank()) {
            rawRoot = System.getenv("APP_DATA_ROOT");
        }
        if (rawRoot == null || rawRoot.isBlank()) {
            rawRoot = "./data";
        }
        String configuredRoot;
        StartupModeResolver.StartupResolution resolution;
        try {
            configuredRoot = DataRootResolver.resolve(rawRoot).toString();
            resolution = StartupModeResolver.resolve(args, configuredRoot,
                    System.getProperty("app.tenant.id", "default"));
        } catch (IllegalStateException e) {
            // argument or data root problems are operator errors, not crashes
            System.err.println("Startup aborted: " + e.getMessage());
            System.exit(2);
            return;
        }
        System.out.println("Data root: " + configuredRoot);

        if (resolution.initDatabase() && !initializeDatabase(resolution)) {
            return;
        }

        Map<String, Object> overrides = StartupPropertyOverrides.of(resolution);

        // The resolution is applied as the first property source: the same keys exist in
        // application.yml, and default properties would lose against them.
        new SpringApplicationBuilder(OtcTestDataApplication.class)
                .initializers(context -> StartupPropertyOverrides.apply(context.getEnvironment(), overrides))
                .run(args);
    }

    /**
     * Wipes the tenant data requested by {@code --init-database}.
     *
     * <p>Runs before the context starts, because the database file is opened by the datasource. A
     * declined prompt or a failed deletion stops the startup instead of running on a half deleted
     * data directory.
     *
     * @return true when the application may start
     */
    private static boolean initializeDatabase(StartupModeResolver.StartupResolution resolution) {
        DatabaseInitializer.Report report = DatabaseInitializer.initialize(
                Path.of(resolution.tenantDirectory()),
                DatabaseInitializer.consoleConfirmation(System.in),
                System.out);
        if (report.status() == DatabaseInitializer.Status.DONE) {
            return true;
        }
        if (report.status() == DatabaseInitializer.Status.DECLINED) {
            System.out.println("Initialization cancelled, the application does not start.");
            System.exit(2);
            return false;
        }
        if (report.status() == DatabaseInitializer.Status.BLOCKED) {
            System.out.println("Initialization refused, the application does not start.");
            System.exit(1);
            return false;
        }
        report.problems().forEach(path -> System.err.println("  cannot delete " + path));
        System.err.println("Initialization failed, the application does not start. "
                + "Stop every instance bound to this tenant and retry.");
        System.exit(1);
        return false;
    }

    /** Kept for tests that need a plain entry point. */
    public static void mainWithoutOverrides(String[] args) {
        SpringApplication.run(OtcTestDataApplication.class, args);
    }
}
