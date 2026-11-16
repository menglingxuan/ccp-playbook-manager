package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The status cards of the task list must report numbers the list itself reproduces. */
class TaskSummaryTest extends AbstractIntegrationTest {

    private int total(String query) {
        var response = api.get("/api/tasks?size=1" + query);
        assertThat(response.ok()).as(response.body()).isTrue();
        return response.json().get("total").asInt();
    }

    private JsonNode summary(String query) {
        var response = api.get("/api/tasks/summary" + query);
        assertThat(response.ok()).as(response.body()).isTrue();
        return response.json();
    }

    private static int sum(JsonNode summary) {
        int total = 0;
        for (String field : summary.propertyNames()) {
            total += summary.get(field).asInt();
        }
        return total;
    }

    @Test
    void everyCardCountIsReproducedByTheListFilter() {
        JsonNode summary = summary("");
        // only statuses an import can end up in are reported; a queued task is never observable
        assertThat(summary.propertyNames()).contains("RUNNING", "SUCCEEDED", "PARTIAL", "FAILED");
        assertThat(summary.propertyNames()).doesNotContain("PENDING");
        assertThat(sum(summary)).isEqualTo(total(""));
        for (String status : summary.propertyNames()) {
            assertThat(total("&status=" + status))
                    .as("list total of status %s", status)
                    .isEqualTo(summary.get(status).asInt());
        }
    }

    @Test
    void cardsFollowTheSearchAndProductFilters() {
        String productCode = productWithFields("MW", "IRS", List.of("tradeId", "tradeDate"));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var upload = api.upload("/api/uploads", "summary_" + suffix + ".csv",
                ("Trade ID,Trade Date\nS-" + suffix + ",2026-01-02\n").getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        var imported = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", upload.json().get("uploadId").asString(),
                "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(imported.ok()).as(imported.body()).isTrue();
        assertThat(imported.json().get("status").asString()).isEqualTo("SUCCEEDED");
        // a commit is one transaction, so no queued or running task can be observed afterwards
        assertThat(summary("").get("RUNNING").asInt()).isZero();
        assertThat(total("&status=RUNNING")).isZero();

        // a product filter narrows both the list and the cards to the same subset
        JsonNode byProduct = summary("?product=IRS");
        assertThat(sum(byProduct)).isEqualTo(total("&product=IRS"));
        for (String status : byProduct.propertyNames()) {
            assertThat(total("&product=IRS&status=" + status)).isEqualTo(byProduct.get(status).asInt());
        }

        // a search without hits empties the cards instead of showing global numbers
        JsonNode none = summary("?q=" + UUID.randomUUID());
        assertThat(sum(none)).isZero();
        assertThat(summary("?q=" + UUID.randomUUID()).propertyNames())
                .contains("RUNNING", "SUCCEEDED", "PARTIAL", "FAILED");
    }

    @Test
    void failedImportIsCountedAndFiltered() {
        String productCode = productWithFields("MW", "IRS", List.of("tradeId"));
        int before = summary("").get("FAILED").asInt();
        // the file has no trade block at all, so the commit cannot produce a batch
        var upload = api.upload("/api/uploads", "broken.csv",
                "Unrelated Column\nvalue\n".getBytes(StandardCharsets.UTF_8));
        assertThat(upload.ok()).as(upload.body()).isTrue();
        var failed = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", upload.json().get("uploadId").asString(),
                "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(failed.ok()).as(failed.body()).isTrue();
        assertThat(failed.json().get("status").asString()).isEqualTo("FAILED");
        String taskId = failed.json().get("taskId").asString();

        int after = summary("").get("FAILED").asInt();
        assertThat(after).isEqualTo(before + 1);
        assertThat(api.get("/api/tasks?size=25&status=FAILED").json().get("total").asInt()).isEqualTo(after);
        var filtered = api.get("/api/tasks?size=100&status=FAILED");
        assertThat(filtered.body()).contains(taskId);
        // the task is gone from the unfiltered list of its own status but stays out of DELETED counting
        assertThat(api.get("/api/tasks?size=100&status=SUCCEEDED").body()).doesNotContain(taskId);
    }
}
