package ac.web.config;

import ac.core.config.AppProperties;
import ac.core.config.TenantContext;
import org.springframework.stereotype.Component;

/** Resolves the tenant of this instance; one instance is bound to one tenant. */
@Component
public class TenantProvider {

    private final AppProperties properties;

    public TenantProvider(AppProperties properties) {
        this.properties = properties;
    }

    public String tenantId() {
        String tenant = TenantContextValue();
        return tenant == null ? properties.getTenant().getId() : tenant;
    }

    private String TenantContextValue() {
        try {
            return TenantContext.requireTenant();
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
