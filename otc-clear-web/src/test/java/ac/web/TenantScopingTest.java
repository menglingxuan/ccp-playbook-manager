package ac.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ac.core.common.ApiException;
import ac.core.service.cases.CaseService;
import ac.core.service.task.TaskService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Cross tenant access must always look like a missing resource. */
class TenantScopingTest extends AbstractIntegrationTest {

    @Autowired
    private CaseService caseService;

    @Autowired
    private TaskService taskService;

    @Test
    void otherTenantCannotReadCasesOrTasks() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String csv = """
                Trade ID,Trade Date,Maturity Date,Notional,Notional Currency,Rate,Rate,Direction
                TEN-%s,2026-01-02,2031-01-02,1000000,USD,0.03,0.04,BUY
                """.formatted(suffix);
        var upload = api.upload("/api/uploads", "tenant_" + suffix + ".csv",
                csv.getBytes(StandardCharsets.UTF_8));
        String uploadId = upload.json().get("uploadId").asString();
        var importResponse = api.post("/api/imports", api.toJson(Map.of(
                "uploadId", uploadId, "force", false,
                "sheets", List.of(Map.of("sheetIndex", 0, "productCode", "IRS")))));
        String taskId = importResponse.json().get("taskId").asString();
        String batchId = importResponse.json().get("batches").get(0).get("batchId").asString();
        var cases = api.get("/api/batches/" + batchId + "/cases?page=1&size=10");
        String caseId = cases.json().get("items").get(0).get("id").asString();

        assertThat(caseService.detail("default", caseId).testCaseId()).isNotBlank();
        assertThatThrownBy(() -> caseService.detail("other-tenant", caseId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("does not exist");
        assertThatThrownBy(() -> taskService.detail("other-tenant", taskId))
                .isInstanceOf(ApiException.class);
        assertThat(taskService.search("other-tenant",
                new ac.core.persistence.TaskFilter(null, null, null, null, "created",
                        ac.core.common.PageQuery.of(1, 10))).total()).isZero();
    }
}
