package ac.web.api;

import ac.core.config.AppProperties;
import ac.core.service.product.ProductCatalog;
import ac.core.service.settings.SettingsService;
import ac.domain.export.CsvFormatOptions;
import ac.web.config.TenantProvider;
import ac.web.dto.ContextView;
import ac.web.dto.ProductView;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Runtime context and product definitions. */
@RestController
@RequestMapping("/api")
public class ContextController {

    private final TenantProvider tenantProvider;
    private final AppProperties properties;
    private final ProductCatalog products;
    private final SettingsService settings;
    private final String version;

    public ContextController(TenantProvider tenantProvider, AppProperties properties, ProductCatalog products,
            SettingsService settings, @Value("${app.version:0.1.0}") String version) {
        this.tenantProvider = tenantProvider;
        this.properties = properties;
        this.products = products;
        this.settings = settings;
        this.version = version;
    }

    @GetMapping("/context")
    public ContextView context() {
        String tenantId = tenantProvider.tenantId();
        CsvFormatOptions options = settings.exportOptions(tenantId);
        Map<String, Object> exportDefaults = new LinkedHashMap<>();
        exportDefaults.put("delimiter", String.valueOf(options.delimiter()));
        exportDefaults.put("quoteAll", options.quoteAll());
        exportDefaults.put("includeHeader", options.includeHeader());
        exportDefaults.put("lineEnding", options.lineEnding().equals("\n") ? "LF" : "CRLF");
        exportDefaults.put("bom", options.bom());
        exportDefaults.put("managedColumns", options.managedColumns().name());
        exportDefaults.put("formulaAsValue", options.formulaAsValue());

        return new ContextView(properties.getMode().name(), tenantId, properties.getDataRoot(), version,
                new ContextView.LimitsView(properties.getLimits().getMaxFileSizeBytes(),
                        properties.getLimits().getMaxCloneCount(),
                        properties.getLimits().getMaxBulkDelete(),
                        properties.getLimits().getMaxRowsPerSheet()),
                exportDefaults,
                products.all(tenantId).stream().map(ProductView::of).toList());
    }
}
