package ac.core.config;

import ac.domain.export.ManagedColumnScope;
import java.time.Duration;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

/** Application configuration bound from the {@code app.*} tree. */
@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private AppMode mode = AppMode.SINGLE;
    private Tenant tenant = new Tenant();
    private String dataRoot = "./data";
    private Limits limits = new Limits();
    private Ids ids = new Ids();
    private Export export = new Export();
    private Audit audit = new Audit();
    private Resources resources = new Resources();

    /** Reserved word friendly accessor for {@code app.import.*}. */
    @Setter(AccessLevel.NONE)
    @Name("import")
    private ImportSettings importRules = new ImportSettings();

    public void setImportRules(ImportSettings importRules) {
        this.importRules = importRules == null ? new ImportSettings() : importRules;
    }

    public ImportSettings getImportRules() {
        return importRules;
    }

    @Getter
    @Setter
    public static class Tenant {
        private String id = "default";
        private boolean allowHeaderOverride = false;
    }

    @Getter
    @Setter
    public static class Limits {
        private long maxFileSizeBytes = 52_428_800L;
        private int maxRowsPerSheet = 200_000;
        private int maxSheets = 50;
        private int maxCloneCount = 200;
        private int maxBulkDelete = 500;
        private long uploadTtlMinutes = 60;
        /** Rows read for the wizard preview; the full parse happens on submit. */
        private int previewRowLimit = 300;
        private int previewSampleRows = 20;
        private int maxIssueDetails = 200;
    }

    @Getter
    @Setter
    public static class Ids {
        private boolean normalizeFormulaRefs = false;
        private int hashLength = 32;
    }

    @Getter
    @Setter
    public static class ImportSettings {
        private boolean rejectDuplicateKey = false;
        private DuplicateAction duplicateOriginalIdAction = DuplicateAction.WARN;
    }

    /** Reaction to an already imported file/sheet combination. */
    public enum DuplicateAction {
        WARN,
        ERROR,
        IGNORE
    }

    /** Audit trail settings; the prod profile turns the whole trail off by default. */
    @Getter
    @Setter
    public static class Audit {
        private boolean enabled = true;
        private int pageSize = 25;
    }

    /** Resource statistics switches; the prod profile hides host level figures by default. */
    @Getter
    @Setter
    public static class Resources {
        private boolean heapMetricsEnabled = true;
        private boolean diskMetricsEnabled = true;
    }

    @Getter
    @Setter
    public static class Export {
        private String defaultDelimiter = ",";
        private boolean defaultQuoteAll = false;
        private String defaultLineEnding = "CRLF";
        private boolean defaultBom = false;
        private ManagedColumnScope defaultManagedColumns = ManagedColumnScope.KNOWN;
    }
}
