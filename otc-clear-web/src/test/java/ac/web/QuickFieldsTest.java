package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The quick field action turns the column names of a CSV file into business field definitions. */
class QuickFieldsTest extends AbstractIntegrationTest {

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private String newBaseProduct() {
        var created = api.post("/api/catalog/platforms/CFETS/base-products",
                api.toJson(Map.of("code", "QF_" + suffix(), "name", "Quick fields")));
        assertThat(created.ok()).as(created.body()).isTrue();
        return created.json().get("id").asString();
    }

    @Test
    void createsOneFieldPerCsvColumn() {
        String baseId = newBaseProduct();
        String csv = "Trade Ref,Trade Date,Notional Currency,Rate\nR-1,2026-01-02,USD,0.0325\n";
        var response = api.upload("/api/catalog/base-products/" + baseId + "/fields/from-csv",
                "columns.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(response.ok()).as(response.body()).isTrue();
        assertThat(response.json().get("headers").asInt()).isEqualTo(4);
        JsonNode created = response.json().get("created");
        assertThat(created).hasSize(4);
        assertThat(created.get(0).asString()).isEqualTo("tradeRef");
        assertThat(created.get(3).asString()).isEqualTo("rate");

        JsonNode fields = api.get("/api/catalog/base-products/" + baseId).json().get("fields");
        assertThat(fields).hasSize(4);
        JsonNode first = fields.get(0);
        assertThat(first.get("header").asString()).isEqualTo("Trade Ref");
        assertThat(first.get("valueTypeCode").asString()).isEqualTo("TEXT");
        assertThat(first.get("roleCode").asString()).isEqualTo("BUSINESS");
        assertThat(first.get("required").asBoolean()).isTrue();
        assertThat(first.get("sortOrder").asInt()).isEqualTo(1);
        for (int index = 0; index < fields.size(); index++) {
            assertThat(fields.get(index).get("sortOrder").asInt()).isEqualTo(index + 1);
        }
    }

    @Test
    void skipsColumnsThatAlreadyExistAndContinuesTheOrder() {
        String baseId = newBaseProduct();
        var manual = api.post("/api/catalog/base-products/" + baseId + "/fields", api.toJson(Map.of(
                "header", "Trade Ref",
                "valueTypeCode", "TEXT",
                "roleCode", "BUSINESS",
                "required", true,
                "sortOrder", 5)));
        assertThat(manual.ok()).as(manual.body()).isTrue();

        String csv = "Trade Ref,Maturity Date\nR-1,2031-01-02\n";
        var response = api.upload("/api/catalog/base-products/" + baseId + "/fields/from-csv",
                "columns.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(response.ok()).as(response.body()).isTrue();
        assertThat(response.json().get("created")).hasSize(1);
        assertThat(response.json().get("created").get(0).asString()).isEqualTo("maturityDate");

        JsonNode fields = api.get("/api/catalog/base-products/" + baseId).json().get("fields");
        assertThat(fields).hasSize(2);
        JsonNode added = fields.get(1);
        assertThat(added.get("fieldKey").asString()).isEqualTo("maturityDate");
        // the order continues after the highest existing one instead of restarting at one
        assertThat(added.get("sortOrder").asInt()).isEqualTo(6);
    }

    @Test
    void rejectsFileWithoutColumnNames() {
        String baseId = newBaseProduct();
        var empty = api.upload("/api/catalog/base-products/" + baseId + "/fields/from-csv",
                "empty.csv", new byte[0]);
        assertThat(empty.ok()).isFalse();
        assertThat(empty.status()).isEqualTo(400);

        var blank = api.upload("/api/catalog/base-products/" + baseId + "/fields/from-csv",
                "blank.csv", "\n\n".getBytes(StandardCharsets.UTF_8));
        assertThat(blank.ok()).isFalse();
        assertThat(blank.status()).isEqualTo(400);

        assertThat(api.get("/api/catalog/base-products/" + baseId).json().get("fields")).isEmpty();
    }
}
