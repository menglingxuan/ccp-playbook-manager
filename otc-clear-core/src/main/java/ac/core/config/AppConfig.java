package ac.core.config;

import ac.core.common.TimeProvider;
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
}
