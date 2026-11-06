package ac.web;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Shared end to end test base: real HTTP server, in-memory database, isolated data root. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:ottest;DB_CLOSE_DELAY=-1",
        "app.data-root=build/test-data",
        "app.external.enabled=false",
        "app.tenant.id=default"
})
public abstract class AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    protected ApiClient api;

    @BeforeEach
    void setUpClient() {
        if (api == null) {
            api = new ApiClient(port);
        }
    }
}
