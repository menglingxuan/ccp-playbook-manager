package ac.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables scheduled polling and cleanup jobs. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
