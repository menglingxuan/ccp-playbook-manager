package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import ac.core.service.product.ProductDefinitionSeeder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Seed version 7 marks the example business fields of {@code MW/IRS} and {@code MW/OIS} required.
 *
 * <p>Catalogue rows of an existing database are aligned by the startup upgrade: a base field that is
 * still optional becomes required, every derived copy follows and the schema hash is recomputed.
 */
class ProductSeedUpgradeTest extends AbstractIntegrationTest {

    /** Same database the application under test uses, see {@link AbstractIntegrationTest}. */
    private static final String JDBC_URL = "jdbc:h2:mem:ottest;DB_CLOSE_DELAY=-1";
    private static final String TENANT = "default";

    /** Mirrors {@code ProductDefinitionSeeder.MARKER_KEY}, which is package private. */
    private static final String MARKER_KEY = "product.seed.version";

    @Autowired
    private ProductDefinitionSeeder seeder;

    private String baseIdOf(String platformCode, String baseCode) {
        for (JsonNode platform : api.get("/api/catalog").json()) {
            if (!platformCode.equals(platform.get("code").asString())) {
                continue;
            }
            for (JsonNode base : platform.get("baseProducts")) {
                if (baseCode.equals(base.get("code").asString())) {
                    return base.get("id").asString();
                }
            }
        }
        throw new IllegalStateException("Base product " + platformCode + "/" + baseCode + " not found");
    }

    private static void execute(String sql, String... params) {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < params.length; index++) {
                statement.setString(index + 1, params[index]);
            }
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean flag(String sql, String... params) {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < params.length; index++) {
                statement.setString(index + 1, params[index]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as("row for %s exists", sql).isTrue();
                return rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(String sql, String... params) {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < params.length; index++) {
                statement.setString(index + 1, params[index]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as("row for %s exists", sql).isTrue();
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void upgradingFromSeedVersionSixRequiresTheExampleBusinessFields() {
        String baseId = baseIdOf("MW", "IRS");
        JsonNode stored = null;
        for (JsonNode field : api.get("/api/catalog/base-products/" + baseId).json().get("fields")) {
            if ("Effective Date".equals(field.get("header").asString())) {
                stored = field;
            }
        }
        assertThat(stored).isNotNull();
        String fieldId = stored.get("id").asString();
        String markerSql = "SELECT setting_value FROM app_setting WHERE tenant_id = ? AND setting_key = ?";

        // relax the base field through the API so the stored hash matches the optional definition
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fieldKey", stored.get("fieldKey").asString());
        payload.put("header", stored.get("header").asString());
        payload.put("displayName", stored.get("displayName").asString());
        payload.put("aliases", List.of());
        payload.put("valueTypeCode", stored.get("valueTypeCode").asString());
        payload.put("roleCode", stored.get("roleCode").asString());
        payload.put("tags", List.of());
        payload.put("required", false);
        payload.put("remark", null);
        payload.put("sortOrder", stored.get("sortOrder").asInt());
        payload.put("validationExpr", null);
        var relaxed = api.patch("/api/catalog/base-products/" + baseId + "/fields/" + fieldId,
                api.toJson(payload));
        assertThat(relaxed.ok()).as(relaxed.body()).isTrue();
        assertThat(flag("SELECT required_flag FROM base_product_field WHERE id = ?", fieldId)).isFalse();
        String hashOptional = text("SELECT schema_hash FROM base_product WHERE id = ?", baseId);

        // the copies of a version 6 catalogue were optional as well, and the marker still says 6
        for (JsonNode product : api.get("/api/catalog/base-products/" + baseId).json().get("products")) {
            execute("UPDATE product_field SET required_flag = FALSE WHERE product_id = ? AND header = ?",
                    product.get("id").asString(), "Effective Date");
        }
        execute("UPDATE app_setting SET setting_value = '6' WHERE tenant_id = ? AND setting_key = ?",
                TENANT, MARKER_KEY);

        seeder.run(null);

        assertThat(flag("SELECT required_flag FROM base_product_field WHERE id = ?", fieldId)).isTrue();
        for (JsonNode product : api.get("/api/catalog/base-products/" + baseId).json().get("products")) {
            String productId = product.get("id").asString();
            // other tests derive products from this base with a subset of the columns
            if ("0".equals(text("SELECT COUNT(*) FROM product_field WHERE product_id = ? AND header = ?",
                    productId, "Effective Date"))) {
                continue;
            }
            assertThat(flag("SELECT required_flag FROM product_field WHERE product_id = ? AND header = ?",
                    productId, "Effective Date"))
                    .as("%s inherits the required flag", product.get("code").asString())
                    .isTrue();
        }
        assertThat(text(markerSql, TENANT, MARKER_KEY)).isEqualTo("7");
        assertThat(text("SELECT schema_hash FROM base_product WHERE id = ?", baseId))
                .as("the required flag is part of the canonical definition, so the hash is recomputed")
                .isNotEqualTo(hashOptional);
    }

    @Test
    void shippedExampleBusinessFieldsAreRequired() {
        for (String baseCode : new String[] {"IRS", "OIS"}) {
            JsonNode fields = api.get("/api/catalog/base-products/" + baseIdOf("MW", baseCode)).json()
                    .get("fields");
            assertThat(fields).isNotEmpty();
            for (JsonNode field : fields) {
                if (!"BUSINESS".equals(field.get("roleCode").asString())) {
                    continue;
                }
                assertThat(field.get("required").asBoolean())
                        .as("%s/%s is required", baseCode, field.get("header").asString())
                        .isTrue();
            }
        }
    }
}
