package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/** Heap and disk figures are switchable; the prod profile switches both off. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:ottest_resources_off;DB_CLOSE_DELAY=-1",
        "app.data-root=build/test-data-resources-off",
        "app.tenant.id=default",
        "app.resources.heap-metrics-enabled=false",
        "app.resources.disk-metrics-enabled=false"
})
class ResourceMetricsToggleTest {

    @LocalServerPort
    private int port;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
    }

    @Test
    void heapAndDiskFiguresAreOmittedWhenDisabled() {
        JsonNode stats = api.get("/api/resources/stats").json();
        assertThat(stats.get("metrics").get("heap").asBoolean()).isFalse();
        assertThat(stats.get("metrics").get("disk").asBoolean()).isFalse();
        assertThat(stats.get("diskTotalBytes").asLong()).isZero();
        assertThat(stats.get("diskFreeBytes").asLong()).isZero();

        JsonNode runtime = stats.get("runtime");
        assertThat(runtime.get("heapUsedBytes").asLong()).isZero();
        assertThat(runtime.get("heapMaxBytes").asLong()).isZero();
        assertThat(runtime.get("nonHeapUsedBytes").asLong()).isZero();
        // the tenant scoped figures are unaffected
        assertThat(runtime.get("threads").asInt()).isPositive();
        assertThat(stats.get("areas").size()).isEqualTo(5);
        assertThat(stats.get("database").get("totalRows").asLong()).isPositive();
    }
}
