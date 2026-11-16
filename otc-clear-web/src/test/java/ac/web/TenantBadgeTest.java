package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** The tenant identifier is only rendered when {@code app.ui.show-tenant} asks for it. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:ottest_tenant_badge;DB_CLOSE_DELAY=-1",
        "app.data-root=build/test-data-tenant-badge",
        "app.tenant.id=default",
        "app.ui.show-tenant=true"
})
class TenantBadgeTest {

    @LocalServerPort
    private int port;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
    }

    @Test
    void tenantBadgeIsRenderedWhenEnabled() {
        assertThat(api.get("/api/context").json().get("showTenant").asBoolean()).isTrue();
        for (String path : new String[] {"/", "/about"}) {
            assertThat(api.get(path).body()).as(path).contains("id=\"ctxBadge\"");
        }
    }
}
