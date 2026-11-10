package ac.core.service.external;

import ac.core.common.ApiException;
import ac.core.common.PageQuery;
import ac.core.common.PageResult;
import ac.core.persistence.ExternalOutputRepository;
import ac.core.persistence.ExternalOutputRow;
import ac.core.persistence.ImportBatchRepository;
import ac.core.persistence.TestCaseRepository;
import ac.core.service.batch.BatchService;
import ac.core.service.cases.CaseService;
import ac.core.service.cases.CaseSummary;
import ac.core.persistence.CaseFilter;
import java.util.List;
import org.springframework.stereotype.Service;

/** Output browsing: separate from the main case list. */
@Service
public class ExternalOutputService {

    private final ExternalOutputRepository outputRepository;
    private final ImportBatchRepository batchRepository;
    private final TestCaseRepository caseRepository;
    private final CaseService caseService;
    private final BatchService batchService;

    public ExternalOutputService(ExternalOutputRepository outputRepository,
            ImportBatchRepository batchRepository, TestCaseRepository caseRepository, CaseService caseService,
            BatchService batchService) {
        this.outputRepository = outputRepository;
        this.batchRepository = batchRepository;
        this.caseRepository = caseRepository;
        this.caseService = caseService;
        this.batchService = batchService;
    }

    public PageResult<ExternalOutputRow> page(String tenantId, String jobId, PageQuery page) {
        return new PageResult<>(outputRepository.page(tenantId, jobId, page), page.page(), page.size(),
                outputRepository.count(tenantId, jobId));
    }

    public ExternalOutputRow require(String tenantId, String outputId) {
        return outputRepository.find(tenantId, outputId)
                .orElseThrow(() -> ApiException.notFound("Output " + outputId + " does not exist"));
    }

    public List<ExternalOutputRow> listByJob(String tenantId, String jobId) {
        return outputRepository.listByJob(tenantId, jobId);
    }

    public PageResult<CaseSummary> cases(String tenantId, String outputId, CaseFilter filter) {
        ExternalOutputRow output = require(tenantId, outputId);
        if (output.importedBatchId() == null) {
            return PageResult.empty(filter.page());
        }
        CaseFilter scoped = new CaseFilter(output.importedBatchId(), filter.query(), filter.hasFormula(),
                filter.page());
        return caseService.search(tenantId, scoped);
    }

    public void delete(String tenantId, String outputId) {
        ExternalOutputRow output = require(tenantId, outputId);
        if (output.importedBatchId() != null) {
            batchRepository.find(tenantId, output.importedBatchId()).ifPresent(batch ->
                    batchService.delete(tenantId, batch.id()));
        }
        outputRepository.delete(tenantId, outputId);
    }

    public int caseCount(String tenantId, String batchId) {
        return batchId == null ? 0 : caseRepository.countByBatch(tenantId, batchId);
    }
}
