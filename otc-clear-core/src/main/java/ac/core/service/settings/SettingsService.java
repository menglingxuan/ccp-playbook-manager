package ac.core.service.settings;

import ac.core.common.JsonSupport;
import ac.core.common.TimeProvider;
import ac.core.config.AppProperties;
import ac.core.persistence.SettingRepository;
import ac.domain.export.CsvFormatOptions;
import ac.domain.export.ManagedColumnScope;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Tenant preferences, currently the export defaults and the shell display options. */
@Service
public class SettingsService {

    public static final String EXPORT_OPTIONS_KEY = "export.options";

    /** Tenant override of the inline issue limit, see {@code app.ui.issue-inline-limit}. */
    public static final String ISSUE_INLINE_LIMIT_KEY = "ui.issueInlineLimit";

    /** Bounds of a sensible list limit: below five the "more" row carries no information. */
    public static final int MIN_ISSUE_INLINE_LIMIT = 5;
    public static final int MAX_ISSUE_INLINE_LIMIT = 200;

    private final SettingRepository repository;
    private final JsonSupport json;
    private final TimeProvider time;
    private final AppProperties properties;

    public SettingsService(SettingRepository repository, JsonSupport json, TimeProvider time,
            AppProperties properties) {
        this.repository = repository;
        this.json = json;
        this.time = time;
        this.properties = properties;
    }

    public Map<String, Object> all(String tenantId) {
        Map<String, Object> values = new LinkedHashMap<>(repository.findAll(tenantId));
        values.put(EXPORT_OPTIONS_KEY, json.toMapOrEmpty(values.get(EXPORT_OPTIONS_KEY) instanceof String text
                ? text : null));
        return values;
    }

    public CsvFormatOptions exportOptions(String tenantId) {
        return repository.find(tenantId, EXPORT_OPTIONS_KEY)
                .map(this::parseOptions)
                .orElseGet(this::defaultOptions);
    }

    public CsvFormatOptions defaultOptions() {
        AppProperties.Export export = properties.getExport();
        return CsvFormatOptions.of(export.getDefaultDelimiter(), export.isDefaultQuoteAll(), true,
                "LF".equalsIgnoreCase(export.getDefaultLineEnding()) ? "\n" : "\r\n",
                export.isDefaultBom(),
                export.getDefaultManagedColumns() == null ? ManagedColumnScope.KNOWN
                        : export.getDefaultManagedColumns(),
                true);
    }

    public void saveExportOptions(String tenantId, CsvFormatOptions options) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("delimiter", String.valueOf(options.delimiter()));
        payload.put("quoteAll", options.quoteAll());
        payload.put("includeHeader", options.includeHeader());
        payload.put("lineEnding", options.lineEnding().equals("\n") ? "LF" : "CRLF");
        payload.put("bom", options.bom());
        payload.put("managedColumns", options.managedColumns().name());
        payload.put("formulaAsValue", options.formulaAsValue());
        repository.put(tenantId, EXPORT_OPTIONS_KEY, json.toJson(payload), time.nowUtc());
    }

    public void put(String tenantId, String key, String value) {
        repository.put(tenantId, key, value, time.nowUtc());
    }

    /**
     * Issue count that may still be listed in place; above it the UI pages through a drawer.
     *
     * <p>A stored tenant preference wins over {@code app.ui.issue-inline-limit}; a malformed or out of
     * range stored value falls back to the configured default instead of breaking the wizard.
     *
     * @param tenantId tenant scope
     * @return limit between {@value #MIN_ISSUE_INLINE_LIMIT} and {@value #MAX_ISSUE_INLINE_LIMIT}
     */
    public int issueInlineLimit(String tenantId) {
        int fallback = clampIssueInlineLimit(properties.getUi().getIssueInlineLimit());
        return repository.find(tenantId, ISSUE_INLINE_LIMIT_KEY)
                .map(SettingsService::parseIntQuietly)
                .map(SettingsService::clampIssueInlineLimit)
                .orElse(fallback);
    }

    /** Stores the tenant preference; the value is clamped to the supported range. */
    public int saveIssueInlineLimit(String tenantId, int limit) {
        int clamped = clampIssueInlineLimit(limit);
        repository.put(tenantId, ISSUE_INLINE_LIMIT_KEY, String.valueOf(clamped), time.nowUtc());
        return clamped;
    }

    private static int parseIntQuietly(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int clampIssueInlineLimit(int value) {
        return Math.max(MIN_ISSUE_INLINE_LIMIT, Math.min(MAX_ISSUE_INLINE_LIMIT, value));
    }

    private CsvFormatOptions parseOptions(String storedJson) {
        Map<String, Object> payload = json.toMapOrEmpty(storedJson);
        if (payload.isEmpty()) {
            return defaultOptions();
        }
        char delimiter = CsvFormatOptions.parseDelimiter(stringValue(payload.get("delimiter"), ","));
        boolean quoteAll = booleanValue(payload.get("quoteAll"), false);
        boolean includeHeader = booleanValue(payload.get("includeHeader"), true);
        String lineEnding = "LF".equalsIgnoreCase(stringValue(payload.get("lineEnding"), "CRLF")) ? "\n" : "\r\n";
        boolean bom = booleanValue(payload.get("bom"), false);
        ManagedColumnScope scope;
        try {
            scope = ManagedColumnScope.valueOf(stringValue(payload.get("managedColumns"), "KNOWN"));
        } catch (IllegalArgumentException e) {
            scope = ManagedColumnScope.KNOWN;
        }
        boolean formulaAsValue = booleanValue(payload.get("formulaAsValue"), true);
        return new CsvFormatOptions(delimiter, quoteAll, includeHeader, lineEnding, bom, scope, formulaAsValue);
    }

    private static String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
