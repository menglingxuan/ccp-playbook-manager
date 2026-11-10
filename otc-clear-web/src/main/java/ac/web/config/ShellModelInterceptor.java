package ac.web.config;

import ac.core.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/**
 * Exposes shell flags to every rendered page.
 *
 * <p>A {@code HandlerInterceptor} is used instead of {@code @ModelAttribute} in a
 * {@code @ControllerAdvice} because the simple pages ({@code /about}, {@code /meta}, …) are
 * registered as view controllers and never run through a handler method.
 */
@Component
public class ShellModelInterceptor implements HandlerInterceptor {

    private final AppProperties properties;

    public ShellModelInterceptor(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
            ModelAndView modelAndView) {
        if (modelAndView != null) {
            modelAndView.addObject("auditEnabled", properties.getAudit().isEnabled());
        }
    }
}
