package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Global catalogue search and the three resolved locales through the real HTTP API. */
class CatalogSearchTest extends AbstractIntegrationTest {

    @Test
    void searchesBaseProductsProductsAndFields() {
        var byBaseName = api.get("/api/catalog/search?q=Interest");
        assertThat(byBaseName.ok()).as(byBaseName.body()).isTrue();
        JsonNode baseHits = byBaseName.json().get("hits");
        assertThat(baseHits).isNotEmpty();
        assertThat(hasKind(baseHits, "BASE_PRODUCT")).isTrue();

        var byHeader = api.get("/api/catalog/search?q=notional");
        assertThat(byHeader.ok()).as(byHeader.body()).isTrue();
        JsonNode fieldHits = byHeader.json().get("hits");
        assertThat(hasKind(fieldHits, "BASE_FIELD")).isTrue();
        JsonNode firstField = firstOfKind(fieldHits, "BASE_FIELD");
        assertThat(firstField.get("header").asString()).isEqualTo("Notional");
        assertThat(firstField.get("fieldId").asString()).isNotBlank();
        assertThat(firstField.get("platformCode").asString()).isEqualTo("MW");
        assertThat(firstField.get("matchedIn").asString()).isEqualTo("HEADER");

        var byProductName = api.get("/api/catalog/search?q=IRS%20Simple");
        assertThat(byProductName.ok()).as(byProductName.body()).isTrue();
        JsonNode productHits = byProductName.json().get("hits");
        assertThat(hasKind(productHits, "PRODUCT")).isTrue();
        JsonNode product = firstOfKind(productHits, "PRODUCT");
        assertThat(product.get("productCode").asString()).isEqualTo("IRS_SIMPLE");
        assertThat(product.get("productId").asString()).isNotBlank();

        assertThat(hasKind(fieldHits, "PRODUCT_FIELD")).isTrue();

        var byTag = api.get("/api/catalog/search?q=%E6%B8%85%E7%AE%97");
        assertThat(byTag.ok()).as(byTag.body()).isTrue();
        assertThat(hasKind(byTag.json().get("hits"), "BASE_FIELD")).isTrue();

        var blank = api.get("/api/catalog/search?q=");
        assertThat(blank.ok()).isTrue();
        assertThat(blank.json().get("total").asInt()).isZero();
    }

    @Test
    void searchCanBeLimitedToOnePlatform() {
        var mw = api.get("/api/catalog/search?platform=MW&q=notional");
        assertThat(mw.ok()).as(mw.body()).isTrue();
        JsonNode mwHits = mw.json().get("hits");
        assertThat(mwHits).isNotEmpty();
        for (JsonNode hit : mwHits) {
            assertThat(hit.get("platformCode").asString()).isEqualTo("MW");
        }
        assertThat(mw.json().get("platform").asString()).isEqualTo("MW");

        var cfets = api.get("/api/catalog/search?platform=CFETS&q=notional");
        assertThat(cfets.ok()).as(cfets.body()).isTrue();
        assertThat(cfets.json().get("total").asInt()).isZero();
    }

    private static boolean hasKind(JsonNode hits, String kind) {
        return firstOfKind(hits, kind) != null;
    }

    private static JsonNode firstOfKind(JsonNode hits, String kind) {
        for (JsonNode hit : hits) {
            if (kind.equals(hit.get("kind").asString())) {
                return hit;
            }
        }
        return null;
    }

    @Test
    void servesThreeLocales() {
        var simplified = api.get("/api/i18n");
        assertThat(simplified.ok()).as(simplified.body()).isTrue();
        JsonNode zh = simplified.json();
        assertThat(zh.get("locale").asString()).startsWith("zh");
        assertThat(zh.get("messages").get("app.title").asString()).contains("测试数据");
        assertThat(zh.get("messages").get("products.column.header").asString()).isEqualTo("列名");

        var hongKong = api.get("/api/i18n?lang=zh-HK");
        assertThat(hongKong.ok()).as(hongKong.body()).isTrue();
        assertThat(hongKong.json().get("locale").asString()).isEqualTo("zh-HK");
        assertThat(hongKong.json().get("messages").get("products.column.header").asString()).isEqualTo("欄名");
        assertThat(hongKong.json().get("messages").get("nav.products").asString()).isEqualTo("產品定義");

        var english = api.get("/api/i18n?lang=en");
        assertThat(english.ok()).as(english.body()).isTrue();
        assertThat(english.json().get("locale").asString()).isEqualTo("en");
        assertThat(english.json().get("messages").get("products.column.header").asString()).isEqualTo("Column");

        var page = api.get("/products?lang=en");
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("Product field definition");
        assertThat(page.body()).contains("Field meta");
    }
}
