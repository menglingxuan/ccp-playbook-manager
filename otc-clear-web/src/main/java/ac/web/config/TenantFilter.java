package ac.web.config;

import ac.core.common.Ids;
import ac.core.config.AppProperties;
import ac.core.config.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the tenant of this instance to the request thread and adds request identifiers to the MDC.
 * Header based tenant override is only honoured when explicitly enabled in configuration.
 */
@Component
public class TenantFilter extends OncePerRequestFilter {

    private final AppProperties properties;

    public TenantFilter(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String tenantId = properties.getTenant().getId();
        if (properties.getTenant().isAllowHeaderOverride()) {
            String headerValue = request.getHeader("X-Tenant-Id");
            if (headerValue != null && headerValue.matches("[a-z0-9-]{1,32}")) {
                tenantId = headerValue;
            }
        }
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = Ids.newId();
        }
        TenantContext.set(tenantId);
        MDC.put("tenant", tenantId);
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("tenant");
            MDC.remove("requestId");
            TenantContext.clear();
        }
    }
}
