package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The required flag of a base field caps the flag of its derived copies.
 *
 * <p>A copy may always become required, it may only stay optional while the base field is optional,
 * tightening a base field needs every copy required first and relaxing a base field leaves the copies
 * untouched. Every scenario checks the stored value in the database, not just the response.
 */
class ProductRequiredRuleTest extends AbstractIntegrationTest {

    /** Same database the application under test uses, see {@link AbstractIntegrationTest}. */
    private static final String JDBC_URL = "jdbc:h2:mem:ottest;DB_CLOSE_DELAY=-1";

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    // ------------------------------------------------------------------ fixtures

    private String newBaseProduct() {
        String code = "RQ" + suffix();
        var created = api.post("/api/catalog/platforms/MW/base-products",
                api.toJson(Map.of("code", code, "name", code)));
        assertThat(created.ok()).as(created.body()).isTrue();
        return created.json().get("id").asString();
    }

    private JsonNode addBaseField(String baseId, String header, String roleCode, boolean required) {
        var created = api.post("/api/catalog/base-products/" + baseId + "/fields", api.toJson(Map.of(
                "header", header, "valueTypeCode", "TEXT", "roleCode", roleCode, "required", required)));
        assertThat(created.ok()).as(created.body()).isTrue();
        return created.json();
    }

    private String newProduct(String baseId) {
        String code = "RQP" + suffix();
        var created = api.post("/api/catalog/base-products/" + baseId + "/products",
                api.toJson(Map.of("code", code, "name", code)));
        assertThat(created.ok()).as(created.body()).isTrue();
        return created.json().get("id").asString();
    }

    private String baseFieldId(String baseId, String header) {
        for (JsonNode field : api.get("/api/catalog/base-products/" + baseId).json().get("fields")) {
            if (header.equals(field.get("header").asString())) {
                return field.get("id").asString();
            }
        }
        throw new AssertionError("Base field " + header + " not found");
    }

    private JsonNode productField(String productId, String header) {
        for (JsonNode field : api.get("/api/catalog/products/" + productId).json().get("fields")) {
            if (header.equals(field.get("header").asString())) {
                return field;
            }
        }
        throw new AssertionError("Product field " + header + " not found");
    }

    private ApiClient.Response setBaseRequired(String baseId, String header, boolean required) {
        JsonNode stored = null;
        for (JsonNode field : api.get("/api/catalog/base-products/" + baseId).json().get("fields")) {
            if (header.equals(field.get("header").asString())) {
                stored = field;
            }
        }
        assertThat(stored).isNotNull();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fieldKey", stored.get("fieldKey").asString());
        payload.put("header", stored.get("header").asString());
        payload.put("displayName", stored.get("displayName").asString());
        payload.put("aliases", List.of());
        payload.put("valueTypeCode", stored.get("valueTypeCode").asString());
        payload.put("roleCode", stored.get("roleCode").asString());
        payload.put("tags", List.of());
        payload.put("required", required);
        payload.put("remark", null);
        payload.put("sortOrder", stored.get("sortOrder").asInt());
        payload.put("validationExpr", null);
        return api.patch("/api/catalog/base-products/" + baseId + "/fields/"
                + stored.get("id").asString(), api.toJson(payload));
    }

    private ApiClient.Response setProductRequired(String productId, String header, boolean required) {
        JsonNode stored = productField(productId, header);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("required", required);
        payload.put("sortOrder", stored.get("sortOrder").asInt());
        payload.put("tags", List.of());
        payload.put("remark", null);
        payload.put("validationExpr", stored.path("validationExpr").isNull() ? null
                : stored.get("validationExpr").asString());
        return api.patch("/api/catalog/products/" + productId + "/fields/"
                + stored.get("id").asString(), api.toJson(payload));
    }

    // ------------------------------------------------------------------ database

    private static boolean requiredFlag(String table, String fieldId) {
        String sql = "SELECT required_flag FROM " + table + " WHERE id = ?";
        try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, fieldId);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as("row %s/%s exists", table, fieldId).isTrue();
                return rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean storedBaseRequired(String fieldId) {
        return requiredFlag("base_product_field", fieldId);
    }

    private static boolean storedProductRequired(String productId, String header) {
        String sql = "SELECT required_flag FROM product_field WHERE product_id = ? AND header = ?";
        try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, productId);
            statement.setString(2, header);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as("product field %s/%s exists", productId, header).isTrue();
                return rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Simulates a database where a copy stayed optional although the base field is required. */
    private static void forceBaseRequired(String fieldId) {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE base_product_field SET required_flag = TRUE WHERE id = ?")) {
            statement.setString(1, fieldId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ scenarios

    /** a) A required base business field keeps its derived copy required. */
    @Test
    void requiredBaseBusinessFieldCannotBeRelaxedInTheProduct() {
        String baseId = newBaseProduct();
        JsonNode baseField = addBaseField(baseId, "Trade Ref", "BUSINESS", true);
        String productId = newProduct(baseId);
        assertThat(storedProductRequired(productId, "Trade Ref")).isTrue();

        var relaxed = setProductRequired(productId, "Trade Ref", false);

        assertThat(relaxed.status()).isEqualTo(409);
        assertThat(relaxed.body()).contains("cannot be relaxed");
        assertThat(storedProductRequired(productId, "Trade Ref")).isTrue();
    }

    /** b) An optional base field leaves the choice to the product, both ways. */
    @Test
    void optionalBaseFieldCanBeTightenedOrKeptOptional() {
        String baseId = newBaseProduct();
        addBaseField(baseId, "Counterparty Ref", "BUSINESS", false);
        String productId = newProduct(baseId);
        assertThat(storedProductRequired(productId, "Counterparty Ref")).isFalse();

        var tightened = setProductRequired(productId, "Counterparty Ref", true);
        assertThat(tightened.ok()).as(tightened.body()).isTrue();
        assertThat(tightened.json().get("required").asBoolean()).isTrue();
        assertThat(storedProductRequired(productId, "Counterparty Ref")).isTrue();

        var relaxed = setProductRequired(productId, "Counterparty Ref", false);
        assertThat(relaxed.ok()).as(relaxed.body()).isTrue();
        assertThat(relaxed.json().get("required").asBoolean()).isFalse();
        assertThat(storedProductRequired(productId, "Counterparty Ref")).isFalse();
    }

    /** c) Tightening a base field is rejected while any copy is still optional. */
    @Test
    void tighteningBaseFieldRequiresEveryCopyToBeRequired() {
        String baseId = newBaseProduct();
        addBaseField(baseId, "Settlement Ref", "BUSINESS", false);
        String fieldId = baseFieldId(baseId, "Settlement Ref");
        String first = newProduct(baseId);
        String second = newProduct(baseId);
        assertThat(storedBaseRequired(fieldId)).isFalse();
        assertThat(storedProductRequired(first, "Settlement Ref")).isFalse();
        assertThat(storedProductRequired(second, "Settlement Ref")).isFalse();

        var blocked = setBaseRequired(baseId, "Settlement Ref", true);
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.body()).contains("still optional in derived products");
        assertThat(storedBaseRequired(fieldId)).isFalse();

        assertThat(setProductRequired(first, "Settlement Ref", true).ok()).isTrue();
        var stillBlocked = setBaseRequired(baseId, "Settlement Ref", true);
        assertThat(stillBlocked.status()).isEqualTo(409);
        assertThat(storedBaseRequired(fieldId)).isFalse();

        assertThat(setProductRequired(second, "Settlement Ref", true).ok()).isTrue();
        var tightened = setBaseRequired(baseId, "Settlement Ref", true);
        assertThat(tightened.ok()).as(tightened.body()).isTrue();
        assertThat(storedBaseRequired(fieldId)).isTrue();
        assertThat(storedProductRequired(first, "Settlement Ref")).isTrue();
        assertThat(storedProductRequired(second, "Settlement Ref")).isTrue();
    }

    /** d) Relaxing a base field keeps the current flags of the derived copies. */
    @Test
    void relaxingBaseFieldLeavesTheCopiesUntouched() {
        String baseId = newBaseProduct();
        addBaseField(baseId, "Booking Ref", "BUSINESS", true);
        String fieldId = baseFieldId(baseId, "Booking Ref");
        String first = newProduct(baseId);
        String second = newProduct(baseId);
        assertThat(storedProductRequired(first, "Booking Ref")).isTrue();
        assertThat(storedProductRequired(second, "Booking Ref")).isTrue();

        var relaxed = setBaseRequired(baseId, "Booking Ref", false);

        assertThat(relaxed.ok()).as(relaxed.body()).isTrue();
        assertThat(storedBaseRequired(fieldId)).isFalse();
        assertThat(storedProductRequired(first, "Booking Ref")).isTrue();
        assertThat(storedProductRequired(second, "Booking Ref")).isTrue();

        // the copies may now be relaxed individually
        assertThat(setProductRequired(first, "Booking Ref", false).ok()).isTrue();
        assertThat(storedProductRequired(first, "Booking Ref")).isFalse();
        assertThat(storedProductRequired(second, "Booking Ref")).isTrue();
    }

    /** e) The rule covers managed columns as well. */
    @Test
    void requiredBaseManagedFieldCannotBeRelaxedInTheProduct() {
        String baseId = newBaseProduct();
        addBaseField(baseId, "_ReviewNote", "MANAGED", true);
        String productId = newProduct(baseId);
        assertThat(storedProductRequired(productId, "_ReviewNote")).isTrue();

        var relaxed = setProductRequired(productId, "_ReviewNote", false);

        assertThat(relaxed.status()).isEqualTo(409);
        assertThat(storedProductRequired(productId, "_ReviewNote")).isTrue();
    }

    /** f) A copy that is still optional can always be tightened, even next to a required base field. */
    @Test
    void optionalCopyNextToARequiredBaseFieldCanBeTightened() {
        String baseId = newBaseProduct();
        addBaseField(baseId, "Legacy Ref", "BUSINESS", false);
        String fieldId = baseFieldId(baseId, "Legacy Ref");
        String productId = newProduct(baseId);
        assertThat(storedProductRequired(productId, "Legacy Ref")).isFalse();
        forceBaseRequired(fieldId);
        assertThat(storedBaseRequired(fieldId)).isTrue();

        var tightened = setProductRequired(productId, "Legacy Ref", true);

        assertThat(tightened.ok()).as(tightened.body()).isTrue();
        assertThat(tightened.json().get("required").asBoolean()).isTrue();
        assertThat(storedProductRequired(productId, "Legacy Ref")).isTrue();
    }
}
