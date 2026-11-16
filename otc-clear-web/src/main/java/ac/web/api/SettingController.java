package ac.web.api;

import ac.core.service.export.CsvOptionsPatch;
import ac.core.service.settings.SettingsService;
import ac.domain.export.CsvFormatOptions;
import ac.web.config.TenantProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant preferences.
 *
 * <p>{@code PUT} merges the submitted fields over the stored options, so a client may send only what
 * it changes.
 */
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
    public Map<String, Object> update(@RequestBody CsvOptionsPatch patch) {
        CsvFormatOptions current = settings.exportOptions(tenantProvider.tenantId());
        settings.saveExportOptions(tenantProvider.tenantId(), patch.merge(current));
        return get();
    }

    /** Shell display preferences, currently the inline limit of the import issue lists. */
    @GetMapping("/ui")
    public Map<String, Object> ui() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("issueInlineLimit", settings.issueInlineLimit(tenantProvider.tenantId()));
        return body;
    }

    /**
     * Updates the issue list limit.
     *
     * <p>The value is clamped to the supported range rather than rejected, so a slightly off number in
     * a client does not leave the wizard without a usable display.
     *
     * @param patch holds an optional {@code issueInlineLimit}
     * @return the stored preferences
     */
    @PutMapping("/ui")
    public Map<String, Object> updateUi(@RequestBody UiPatch patch) {
        if (patch != null && patch.issueInlineLimit() != null) {
            settings.saveIssueInlineLimit(tenantProvider.tenantId(), patch.issueInlineLimit());
        }
        return ui();
    }

    /**
     * Partial update of the shell preferences.
     *
     * @param issueInlineLimit issues listed in place before the paginated panel opens
     */
    public record UiPatch(Integer issueInlineLimit) {
    }
}
