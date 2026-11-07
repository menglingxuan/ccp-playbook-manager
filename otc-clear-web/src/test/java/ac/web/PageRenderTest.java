package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Server rendered pages must return 200 and contain their shell markers. */
class PageRenderTest extends AbstractIntegrationTest {

    @Test
    void pagesRender() {
        String[] paths = {"/", "/import", "/products", "/meta", "/about", "/outputs", "/external-jobs"};
        for (String path : paths) {
            var response = api.get(path);
            assertThat(response.status()).as(path + " -> " + response.body()).isEqualTo(200);
            assertThat(response.body()).contains("OTC Clear 测试数据管理平台");
        }
    }

    @Test
    void unknownPageReturns404() {
        assertThat(api.get("/nope").status()).isEqualTo(404);
    }

    @Test
    void contextEndpointExposesProducts() {
        var response = api.get("/api/context");
        assertThat(response.ok()).isTrue();
        String body = response.body();
        assertThat(body).contains("IRS").contains("OIS").contains("CCSIRS");
        assertThat(body).contains("schemaHash");
    }

    @Test
    void productApiMarksDuplicateHeaders() {
        var response = api.get("/api/products/IRS");
        assertThat(response.ok()).isTrue();
        String body = response.body();
        assertThat(body).contains("\"header\":\"Rate\"");
        assertThat(body).contains("\"duplicate\":true");
    }
}
