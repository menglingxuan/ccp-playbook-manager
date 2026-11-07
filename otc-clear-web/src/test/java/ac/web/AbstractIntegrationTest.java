package ac.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

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

    private static final Map<String, String> CREATED_PRODUCTS = new ConcurrentHashMap<>();

    @BeforeEach
    void setUpClient() {
        if (api == null) {
            api = new ApiClient(port);
        }
    }

    /**
     * Creates a derived product that keeps only the given base fields.
     *
     * <p>Derived products require every business field, so partial import fixtures need a product
     * whose field set matches the file.
     *
     * @return product code of the new product
     */
    protected String productWithFields(String platformCode, String baseCode, List<String> keepFieldKeys) {
        String baseId = null;
        for (JsonNode platform : api.get("/api/catalog").json()) {
            if (!platformCode.equals(platform.get("code").asString())) {
                continue;
            }
            for (JsonNode base : platform.get("baseProducts")) {
                if (baseCode.equals(base.get("code").asString())) {
                    baseId = base.get("id").asString();
                }
            }
        }
        if (baseId == null) {
            throw new IllegalStateException("Base product " + platformCode + "/" + baseCode + " not found");
        }
        List<String> excluded = new ArrayList<>();
        for (JsonNode field : api.get("/api/catalog/base-products/" + baseId).json().get("fields")) {
            String fieldKey = field.get("fieldKey").asString();
            if (!keepFieldKeys.contains(fieldKey)) {
                excluded.add(fieldKey);
            }
        }
        String code = baseCode + "_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("name", code);
        payload.put("excludedFieldKeys", excluded);
        var created = api.post("/api/catalog/base-products/" + baseId + "/products", api.toJson(payload));
        if (!created.ok()) {
            throw new IllegalStateException("Cannot create product: " + created.body());
        }
        CREATED_PRODUCTS.put(code, created.json().get("id").asString());
        return code;
    }

    /** Id of a product created through {@link #productWithFields}. */
    protected String productIdOf(String productCode) {
        String id = CREATED_PRODUCTS.get(productCode);
        if (id == null) {
            throw new IllegalStateException("Product " + productCode + " was not created by this test");
        }
        return id;
    }
}
