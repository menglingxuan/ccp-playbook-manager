package ac.core.service.external;

import ac.core.common.ApiException;
import ac.core.common.ErrorCode;
import ac.core.common.JsonSupport;
import ac.core.config.AppProperties;
import ac.core.persistence.ExternalJobRow;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** REST implementation of {@link BatchImportClient} driven entirely by configuration. */
@Slf4j
public class HttpBatchImportClient implements BatchImportClient {

    private final RestClient client;
    private final AppProperties.External config;
    private final JsonSupport json;

    public HttpBatchImportClient(AppProperties.External config, JsonSupport json) {
        this.config = config;
        this.json = json;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(config.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(config.getReadTimeout());
        this.client = RestClient.builder()
                .baseUrl(config.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public DispatchResult dispatch(ExternalJobRow job, DispatchPayload payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", payload.requestId());
        body.put("productType", payload.productType());
        body.put("workingFolder", payload.workingFolder());
        body.put("inputFile", payload.inputFile());
        body.put("rowCount", payload.rowCount());
        if (payload.callbackUrl() != null && !payload.callbackUrl().isBlank()) {
            body.put("callbackUrl", payload.callbackUrl());
        }
        String response;
        try {
            response = client.post()
                    .uri(config.getDispatchPath())
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (ResourceAccessException e) {
            throw new ApiException(ErrorCode.EXTERNAL_UNAVAILABLE,
                    "无法连接外部批量导入服务（" + config.getBaseUrl() + config.getDispatchPath() + "）："
                            + rootCauseMessage(e), Map.of("baseUrl", config.getBaseUrl()));
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.EXTERNAL_UNAVAILABLE,
                    "外部批量导入服务返回错误：" + e.getMessage(), Map.of("baseUrl", config.getBaseUrl()));
        }
        Map<String, Object> parsed = json.toMapOrEmpty(response);
        String externalJobId = firstNonBlank(parsed, "jobId", "externalJobId", "id", "executionId");
        return new DispatchResult(externalJobId, response);
    }

    @Override
    public ExternalStatus status(String externalJobId) {
        if (externalJobId == null || externalJobId.isBlank()) {
            return ExternalStatus.unknown("no external job id");
        }
        String path = config.getStatusPath().replace("{externalJobId}", externalJobId);
        String response;
        try {
            response = client.get().uri(path).retrieve().body(String.class);
        } catch (RuntimeException e) {
            log.warn("External status call failed for {}: {}", externalJobId, e.getMessage());
            return ExternalStatus.unknown(e.getMessage());
        }
        Map<String, Object> parsed = json.toMapOrEmpty(response);
        String rawStatus = firstNonBlank(parsed, "status", "state", "jobStatus");
        String message = firstNonBlank(parsed, "message", "error", "detail");
        return new ExternalStatus(rawStatus, ExternalJobStatus.fromExternal(rawStatus), message, response);
    }

    @Override
    public boolean stop(String externalJobId) {
        if (externalJobId == null || externalJobId.isBlank()) {
            return false;
        }
        String path = config.getStopPath().replace("{externalJobId}", externalJobId);
        try {
            client.post().uri(path).retrieve().body(String.class);
            return true;
        } catch (RuntimeException e) {
            log.warn("External stop call failed for {}: {}", externalJobId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean enabled() {
        return config.isEnabled() && config.getBaseUrl() != null && !config.getBaseUrl().isBlank();
    }

    private static String firstNonBlank(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static String rootCauseMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
