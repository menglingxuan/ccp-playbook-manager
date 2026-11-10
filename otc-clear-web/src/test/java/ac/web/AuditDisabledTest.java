package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** The prod profile switches the audit entry off: no writes, page and API answer 404. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:ottest_audit_off;DB_CLOSE_DELAY=-1",
        "app.data-root=build/test-data-audit-off",
        "app.tenant.id=default",
        "app.audit.enabled=false"
})
class AuditDisabledTest {

    @LocalServerPort
    private int port;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port);
    }

    @Test
    void auditEntriesAndEndpointsAreHidden() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", "OFF_" + suffix.toUpperCase());
        payload.put("name", "off");
        assertThat(api.post("/api/catalog/platforms/MW/base-products", api.toJson(payload)).ok()).isTrue();

        assertThat(api.get("/audit").status()).isEqualTo(404);
        assertThat(api.get("/api/audit").status()).isEqualTo(404);
        assertThat(api.get("/api/audit/facets").status()).isEqualTo(404);

        // the context still tells the shell to hide the entry
        assertThat(api.get("/api/context").json().get("auditEnabled").asBoolean()).isFalse();
        // and no page offers it, neither controller-backed nor view-controller-backed
        for (String path : new String[] {"/", "/meta", "/resources", "/about"}) {
            assertThat(api.get(path).body()).as(path).doesNotContain("href=\"/audit\"");
        }
        // the about page mentions nothing about auditing at all
        assertThat(api.get("/about").body()).doesNotContain("审计").doesNotContain("audit");
        assertThat(api.get("/api/context").json().get("database").asString()).contains("H2");
    }
}
