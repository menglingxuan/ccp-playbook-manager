package ac.web.api;

import ac.core.service.settings.SettingsService;
import ac.domain.export.CsvFormatOptions;
import ac.domain.export.ManagedColumnScope;
import ac.web.config.TenantProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tenant preferences. */
@RestController
@RequestMapping("/api/settings")
public class SettingController {

    private final SettingsService settings;
    private final TenantProvider tenantProvider;

    public SettingController(SettingsService settings, TenantProvider tenantProvider) {
        this.settings = settings;
        this.tenantProvider = tenantProvider;
    }

    @GetMapping
    public Map<String, Object> get() {
        CsvFormatOptions options = settings.exportOptions(tenantProvider.tenantId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("delimiter", String.valueOf(options.delimiter()));
        body.put("quoteAll", options.quoteAll());
        body.put("includeHeader", options.includeHeader());
        body.put("lineEnding", options.lineEnding().equals("\n") ? "LF" : "CRLF");
        body.put("bom", options.bom());
        body.put("managedColumns", options.managedColumns().name());
        body.put("formulaAsValue", options.formulaAsValue());
        return body;
    }

    @PutMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> body) {
        char delimiter = CsvFormatOptions.parseDelimiter(string(body.get("delimiter"), ","));
        boolean quoteAll = bool(body.get("quoteAll"), false);
        boolean includeHeader = bool(body.get("includeHeader"), true);
        String lineEnding = "LF".equalsIgnoreCase(string(body.get("lineEnding"), "CRLF")) ? "\n" : "\r\n";
        boolean bom = bool(body.get("bom"), false);
        ManagedColumnScope scope;
        try {
            scope = ManagedColumnScope.valueOf(string(body.get("managedColumns"), "KNOWN"));
        } catch (IllegalArgumentException e) {
            scope = ManagedColumnScope.KNOWN;
        }
        boolean formulaAsValue = bool(body.get("formulaAsValue"), true);
        settings.saveExportOptions(tenantProvider.tenantId(),
                new CsvFormatOptions(delimiter, quoteAll, includeHeader, lineEnding, bom, scope, formulaAsValue));
        return get();
    }

    private static String string(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
