package ac.core.service.external;

import ac.core.common.JsonSupport;
import ac.core.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Chooses the batch import client implementation from configuration. */
@Configuration
public class ExternalClientConfig {

    @Bean
    public BatchImportClient batchImportClient(AppProperties properties, JsonSupport json) {
        AppProperties.External external = properties.getExternal();
        if (external.isEnabled() && external.getBaseUrl() != null && !external.getBaseUrl().isBlank()) {
            return new HttpBatchImportClient(external, json);
        }
        return new DisabledBatchImportClient();
    }
}
