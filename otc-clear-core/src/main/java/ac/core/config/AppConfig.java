package ac.core.config;

import ac.core.common.TimeProvider;
import ac.domain.product.ProductRegistry;
import ac.domain.product.products.CcsIrsCase;
import ac.domain.product.products.IrsCase;
import ac.domain.product.products.OisCase;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Core beans shared by services. */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig {

    @Bean
    public TimeProvider timeProvider() {
        return new TimeProvider(Clock.systemUTC());
    }

    @Bean
    public ProductRegistry productRegistry() {
        return ProductRegistry.of(IrsCase.class, OisCase.class, CcsIrsCase.class);
    }
}
