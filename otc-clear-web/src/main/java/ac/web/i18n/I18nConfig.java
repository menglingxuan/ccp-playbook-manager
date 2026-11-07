package ac.web.i18n;

import java.time.Duration;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/**
 * Wires the three supported locales: Simplified Chinese (default), Hong Kong Simplified Chinese and
 * English.
 *
 * <p>The choice is stored in the {@code lang} cookie and can also be switched with a
 * {@code ?lang=} query parameter; the browser UI reloads after changing it.
 */
@Configuration
public class I18nConfig implements WebMvcConfigurer {

    public static final String LANG_COOKIE = "lang";
    public static final Locale DEFAULT_LOCALE = Locale.SIMPLIFIED_CHINESE;

    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(true);
        return source;
    }

    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver(LANG_COOKIE);
        resolver.setDefaultLocale(DEFAULT_LOCALE);
        resolver.setCookieMaxAge(Duration.ofDays(365));
        resolver.setCookiePath("/");
        resolver.setCookieSameSite("Lax");
        return resolver;
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName(LANG_COOKIE);
        return interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
    }
}
