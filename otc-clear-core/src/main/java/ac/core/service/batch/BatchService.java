package ac.core.service.batch;

import ac.core.common.ApiException;
import ac.core.common.PageResult;
import ac.core.common.TimeProvider;
import ac.core.persistence.BatchFilter;
import ac.core.persistence.CaseFilter;
import ac.core.persistence.ImportBatchRepository;
import ac.core.persistence.ImportBatchRow;
import ac.core.persistence.TestCaseRepository;
import ac.core.service.audit.AuditService;
import ac.core.service.cases.CaseSummary;
import ac.core.service.cases.CaseService;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Batch level queries and mutations. */
@Service
public class BatchService {

    private final ImportBatchRepository batchRepository;
    private final TestCaseRepository caseRepository;
    private final CaseService caseService;
    private final AuditService audit;
    private final TimeProvider time;

    public BatchService(ImportBatchRepository batchRepository, TestCaseRepository caseRepository,
            CaseService caseService, AuditService audit, TimeProvider time) {
        this.batchRepository = batchRepository;
        this.caseRepository = caseRepository;
        this.caseService = caseService;
        this.audit = audit;
        this.time = time;
    }

    public PageResult<ImportBatchRow> search(String tenantId, BatchFilter filter) {
        var items = batchRepository.search(tenantId, filter);
        long total = batchRepository.count(tenantId, filter);
        return new PageResult<>(items, filter.page().page(), filter.page().size(), total);
    }

    public ImportBatchRow require(String tenantId, String batchId) {
        return batchRepository.find(tenantId, batchId)
                .filter(row -> !"DELETED".equals(row.status()))
                .orElseThrow(() -> ApiException.notFound("Batch " + batchId + " does not exist"));
    }

    public PageResult<CaseSummary> cases(String tenantId, String batchId, CaseFilter filter) {
        require(tenantId, batchId);
        return caseService.search(tenantId, filter);
    }

    public void updateNote(String tenantId, String batchId, String note) {
        require(tenantId, batchId);
        batchRepository.updateNote(tenantId, batchId, note, time.nowUtc());
        audit.record(tenantId, AuditService.ENTITY_BATCH, batchId, "UPDATE", Map.of("note", note));
    }

    @Transactional
    public void delete(String tenantId, String batchId) {
        require(tenantId, batchId);
        LocalDateTime now = time.nowUtc();
        caseRepository.softDeleteByBatch(tenantId, batchId, now);
        batchRepository.softDelete(tenantId, batchId, now);
        audit.record(tenantId, AuditService.ENTITY_BATCH, batchId, "DELETE", Map.of());
    }

    public int activeCaseCount(String tenantId, String batchId) {
        return caseRepository.countByBatch(tenantId, batchId);
    }
}
