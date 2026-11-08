package ac.web;

import static org.assertj.core.api.Assertions.assertThat;

import ac.core.persistence.ExternalJobRow;
import ac.core.service.external.BatchImportClient;
import ac.core.service.external.DispatchPayload;
import ac.core.service.external.DispatchResult;
import ac.core.service.external.ExternalJobStatus;
import ac.core.service.external.ExternalStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;

/** External job dispatch, callback and output scanning with a stubbed external tool. */
class ExternalJobFlowTest extends AbstractIntegrationTest {

    private static final String CSV = """
            Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Direction
            %s,2026-01-02,2031-01-02,1000000,USD,0.03,BUY
            """;

    @TestConfiguration
    static class FakeClientConfiguration {

        @Bean
        @Primary
        BatchImportClient fakeBatchImportClient() {
            return new FakeBatchImportClient();
        }
    }

    static class FakeBatchImportClient implements BatchImportClient {

        @Override
        public DispatchResult dispatch(ExternalJobRow job, DispatchPayload payload) {
            return new DispatchResult("EXT-" + job.id(), "{\"jobId\":\"EXT-" + job.id() + "\"}");
        }

        @Override
        public ExternalStatus status(String externalJobId) {
            return new ExternalStatus("success", ExternalJobStatus.SUCCEEDED, "done", "{\"status\":\"success\"}");
        }

        @Override
        public boolean stop(String externalJobId) {
            return true;
        }

        @Override
        public boolean enabled() {
            return true;
        }
    }

    @Test
    void dispatchesScansAndBrowsesOutputs() throws IOException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tradeId = "EXT-" + suffix;
        String productCode = productWithFields("MW", "IRS", List.of("testCaseId", "remarks", "tradeId", "tradeDate", "maturityDate",
                "notional", "notionalCurrency", "fixedRate", "direction"));
        var upload = api.upload("/api/uploads", "ext_" + suffix + ".csv",
                CSV.formatted(tradeId).getBytes(StandardCharsets.UTF_8));
        String uploadId = upload.json().get("uploadId").asString();
        var importResponse = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", uploadId, "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", productCode)))));
        assertThat(importResponse.ok()).as(importResponse.body()).isTrue();
        String batchId = importResponse.json().get("batches").get(0).get("batchId").asString();
        var cases = api.get("/api/batches/" + batchId + "/cases?page=1&size=10");
        String caseId = cases.json().get("items").get(0).get("id").asString();

        var precheck = api.post("/api/external-jobs/precheck",
                api.toJson(Map.of("caseIds", List.of(caseId))));
        assertThat(precheck.ok()).as(precheck.body()).isTrue();
        assertThat(precheck.json().get("valid").asBoolean()).isTrue();
        assertThat(precheck.json().get("serviceEnabled").asBoolean()).isTrue();

        var created = api.post("/api/external-jobs", api.toJson(Map.of("caseIds", List.of(caseId))));
        assertThat(created.ok()).as(created.body()).isTrue();
        JsonNode job = created.json();
        String jobId = job.get("id").asString();
        assertThat(job.get("status").asString()).isEqualTo("DISPATCHED");
        Path folder = Path.of(job.get("folderPath").asString());
        assertThat(Files.isDirectory(folder)).isTrue();
        assertThat(Files.exists(folder.resolve("input").resolve(jobId + ".csv"))).isTrue();

        Path outputDir = Files.createDirectories(folder.resolve("output"));
        Files.writeString(outputDir.resolve("result.csv"), CSV.formatted(tradeId + "-OUT"),
                StandardCharsets.UTF_8);

        var scan = api.post("/api/external-jobs/" + jobId + "/scan", "{}");
        assertThat(scan.ok()).as(scan.body()).isTrue();
        assertThat(scan.json().get("discovered").asInt()).isEqualTo(1);
        assertThat(scan.json().get("imported").asInt()).isEqualTo(1);

        var outputs = api.get("/api/outputs?page=1&size=10");
        assertThat(outputs.json().get("total").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode output = outputs.json().get("items").get(0);
        assertThat(output.get("status").asString()).isEqualTo("IMPORTED");

        var outputCases = api.get("/api/outputs/" + output.get("id").asString() + "/cases?page=1&size=10");
        assertThat(outputCases.json().get("total").asInt()).isEqualTo(1);

        var mainBatches = api.get("/api/batches?includeExternal=false&page=1&size=50");
        assertThat(mainBatches.json().get("items").toString()).doesNotContain("EXTERNAL_OUTPUT");
        var allBatches = api.get("/api/batches?includeExternal=true&page=1&size=100");
        assertThat(allBatches.json().get("items").toString()).contains("EXTERNAL_OUTPUT");

        var callback = api.post("/api/external-jobs/" + jobId + "/callback",
                api.toJson(Map.of("token", "change-me", "status", "success", "message", "done")));
        assertThat(callback.ok()).as(callback.body()).isTrue();
        assertThat(api.get("/api/external-jobs/" + jobId).json().get("status").asString())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void rejectsCallbackWithWrongToken() {
        var response = api.post("/api/external-jobs/00000000-0000-0000-0000-000000000000/callback",
                api.toJson(Map.of("token", "wrong", "status", "success")));
        assertThat(response.status()).isIn(400, 404);
    }
}
