package ac.web.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** MVC tweaks: simple view controllers for the static pages and shell model flags. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ShellModelInterceptor shellModelInterceptor;

    public WebConfig(ShellModelInterceptor shellModelInterceptor) {
        this.shellModelInterceptor = shellModelInterceptor;
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/about").setViewName("about");
        registry.addViewController("/products").setViewName("products");
        registry.addViewController("/meta").setViewName("meta");
        registry.addViewController("/resources").setViewName("resources");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(shellModelInterceptor);
    }
}
