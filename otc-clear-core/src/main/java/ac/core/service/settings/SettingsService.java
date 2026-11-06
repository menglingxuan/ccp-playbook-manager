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

/** Tenant preferences, currently the export defaults. */
@Service
public class SettingsService {

    public static final String EXPORT_OPTIONS_KEY = "export.options";

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
