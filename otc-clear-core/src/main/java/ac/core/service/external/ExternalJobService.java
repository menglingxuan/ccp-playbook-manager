package ac.core.service.external;

import ac.core.common.ApiException;
import ac.core.common.ErrorCode;
import ac.core.common.Ids;
import ac.core.common.JsonSupport;
import ac.core.common.PageQuery;
import ac.core.common.PageResult;
import ac.core.common.TimeProvider;
import ac.core.config.AppProperties;
import ac.core.persistence.ExternalJobRepository;
import ac.core.persistence.ExternalJobRow;
import ac.core.persistence.TestCaseRecord;
import ac.core.service.DataLayout;
import ac.core.service.audit.AuditService;
import ac.core.service.cases.CaseService;
import ac.core.service.export.ExportRequest;
import ac.core.service.export.ExportService;
import ac.core.service.settings.SettingsService;
import ac.domain.casefile.ValidationIssue;
import ac.domain.export.CsvFormatOptions;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Creates, dispatches and tracks external batch import jobs. */
@Slf4j
@Service
public class ExternalJobService {

    private final ExternalJobRepository jobRepository;
    private final BatchImportClient client;
    private final ExportService exportService;
    private final CaseService caseService;
    private final SettingsService settings;
    private final JobFolderAllocator folderAllocator;
    private final OutputScanner outputScanner;
    private final DataLayout dataLayout;
    private final AppProperties properties;
    private final JsonSupport json;
    private final TimeProvider time;
    private final AuditService audit;

    public ExternalJobService(ExternalJobRepository jobRepository, BatchImportClient client,
            ExportService exportService, CaseService caseService, SettingsService settings,
            JobFolderAllocator folderAllocator, OutputScanner outputScanner, DataLayout dataLayout,
            AppProperties properties, JsonSupport json, TimeProvider time, AuditService audit) {
        this.jobRepository = jobRepository;
        this.client = client;
        this.exportService = exportService;
        this.caseService = caseService;
        this.settings = settings;
        this.folderAllocator = folderAllocator;
        this.outputScanner = outputScanner;
        this.dataLayout = dataLayout;
        this.properties = properties;
        this.json = json;
        this.time = time;
        this.audit = audit;
    }

    public ExternalPrecheck precheck(String tenantId, ExternalJobRequest request) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (!client.enabled()) {
            issues.add(ValidationIssue.warn("EXTERNAL_DISABLED",
                    "The external batch import tool is not configured (app.external.enabled=false)"));
        }
        if (request.caseIds().isEmpty()) {
            issues.add(ValidationIssue.error("NO_SELECTION", "Select at least one case"));
        }
        int rowCount = request.caseIds().size();
        if (rowCount > 0) {
            try {
                var precheck = exportService.precheck(tenantId,
                        new ExportRequest(null, request.caseIds(), options(tenantId, request)));
                issues.addAll(precheck.issues());
                rowCount = precheck.rowCount();
            } catch (RuntimeException e) {
                issues.add(ValidationIssue.error("PRECHECK_FAILED", e.getMessage()));
            }
        }
        boolean valid = issues.stream().noneMatch(issue -> issue.level() == ac.domain.casefile.IssueLevel.ERROR);
        return new ExternalPrecheck(valid, client.enabled(), rowCount, issues);
    }

    public ExternalJobRow create(String tenantId, ExternalJobRequest request) {
        ExternalPrecheck precheck = precheck(tenantId, request);
        if (!precheck.valid()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Dispatch precheck failed",
                    Map.of("issues", precheck.issues()));
        }
        if (!client.enabled()) {
            throw new ApiException(ErrorCode.EXTERNAL_UNAVAILABLE,
                    "The external batch import tool is disabled (app.external.enabled=false)");
        }

        CsvFormatOptions options = options(tenantId, request);
        var content = exportService.content(tenantId, new ExportRequest(null, request.caseIds(), options));
        List<TestCaseRecord> cases = caseService.requireAll(tenantId, request.caseIds());
        String productCode = cases.get(0).productCode();

        String jobId = Ids.newId();
        String requestId = jobId;
        LocalDate today = LocalDate.now(TimeProvider.DISPLAY_ZONE);
        var files = folderAllocator.allocate(tenantId, jobId, productCode, requestId, content, options, today);

        LocalDateTime now = time.nowUtc();
        String payload = json.toJson(Map.of(
                "requestId", requestId,
                "productType", productCode,
                "workingFolder", files.folder().toString(),
                "inputFile", files.inputCsv().toString(),
                "rowCount", content.rows().size()));
        ExternalJobRow job = new ExternalJobRow(jobId, tenantId, productCode, content.rows().size(),
                files.folder().toString(), files.inputCsv().toString(), requestId, null, "PENDING", payload,
                null, null, "system", now, null, null, null);
        jobRepository.insert(job);
        jobRepository.insertCases(tenantId, jobId, request.caseIds());

        DispatchPayload dispatch = new DispatchPayload(requestId, productCode, files.folder().toString(),
                files.inputCsv().toString(), content.rows().size(), callbackUrl(jobId));
        try {
            DispatchResult result = client.dispatch(job, dispatch);
            jobRepository.updateDispatch(tenantId, jobId, result.externalJobId(), "DISPATCHED",
                    time.nowUtc(), json.toJson(dispatchMap(dispatch)), result.rawResponse(), null);
            audit.record(tenantId, AuditService.ENTITY_EXTERNAL_JOB, jobId, "DISPATCH",
                    Map.of("rows", content.rows().size(), "externalJobId",
                            String.valueOf(result.externalJobId())));
        } catch (RuntimeException e) {
            log.warn("Dispatch of job {} failed: {}", jobId, e.getMessage());
            jobRepository.updateDispatch(tenantId, jobId, null, "FAILED", time.nowUtc(),
                    json.toJson(dispatchMap(dispatch)), null, e.getMessage());
            audit.record(tenantId, AuditService.ENTITY_EXTERNAL_JOB, jobId, "DISPATCH",
                    Map.of("error", String.valueOf(e.getMessage())));
        }
        return require(tenantId, jobId);
    }

    public PageResult<ExternalJobRow> search(String tenantId, String status, String product, String query,
            PageQuery page) {
        return new PageResult<>(jobRepository.page(tenantId, status, product, query, page), page.page(),
                page.size(), jobRepository.count(tenantId, status, product, query));
    }

    public ExternalJobRow require(String tenantId, String jobId) {
        return jobRepository.find(tenantId, jobId)
                .orElseThrow(() -> ApiException.notFound("External job " + jobId + " does not exist"));
    }

    /** Pulls the current status from the external tool. */
    public ExternalJobRow refresh(String tenantId, String jobId) {
        ExternalJobRow job = require(tenantId, jobId);
        return refreshStatus(tenantId, job);
    }

    public ExternalJobRow refreshStatus(String tenantId, ExternalJobRow job) {
        if (!job.status().equals("PENDING") && ExternalJobStatus.valueOf(job.status()).terminal()) {
            return job;
        }
        if (job.externalJobId() == null || job.externalJobId().isBlank()) {
            return job;
        }
        LocalDateTime now = time.nowUtc();
        ExternalStatus status = client.status(job.externalJobId());
        String newStatus = status.mapped().name();
        LocalDateTime finished = status.mapped().terminal() ? now : null;
        jobRepository.updateStatus(tenantId, job.id(), newStatus,
                status.body() == null ? job.lastResponse() : status.body(),
                status.message(), finished, now);
        return require(tenantId, job.id());
    }

    public ExternalJobRow stop(String tenantId, String jobId) {
        ExternalJobRow job = require(tenantId, jobId);
        boolean acknowledged = client.stop(job.externalJobId());
        String message = acknowledged ? "Stop acknowledged by the external tool"
                : "Stop requested; the external tool did not confirm";
        jobRepository.updateStatus(tenantId, jobId, "STOPPED", job.lastResponse(), message, time.nowUtc(),
                time.nowUtc());
        audit.record(tenantId, AuditService.ENTITY_EXTERNAL_JOB, jobId, "STOP", Map.of("message", message));
        return require(tenantId, jobId);
    }

    /** Callback entry point; the token is validated when configured. */
    public void callback(String tenantId, String jobId, String token, String statusText, String message) {
        String expected = properties.getExternal().getCallbackToken();
        if (expected != null && !expected.isBlank() && !expected.equals(token)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Invalid callback token");
        }
        ExternalJobRow job = require(tenantId, jobId);
        ExternalJobStatus mapped = ExternalJobStatus.fromExternal(statusText);
        String response = json.toJson(Map.of("status", statusText == null ? "" : statusText,
                "message", message == null ? "" : message));
        jobRepository.updateStatus(tenantId, jobId, mapped.name(), response, message,
                mapped.terminal() ? time.nowUtc() : null, time.nowUtc());
        audit.record(tenantId, AuditService.ENTITY_EXTERNAL_JOB, jobId, "CALLBACK",
                Map.of("status", mapped.name()));
    }

    public ScanResult scan(String tenantId, String jobId) {
        ExternalJobRow job = require(tenantId, jobId);
        ScanResult result = outputScanner.scan(tenantId, job);
        audit.record(tenantId, AuditService.ENTITY_EXTERNAL_JOB, jobId, "SCAN",
                Map.of("imported", result.imported(), "failed", result.failed()));
        return result;
    }

    public List<ExternalJobRow> activeJobs(String tenantId) {
        return jobRepository.activeJobs(tenantId);
    }

    public String callbackUrl(String jobId) {
        String base = properties.getExternal().getCallbackBaseUrl();
        if (base == null || base.isBlank()) {
            return "";
        }
        return base.replaceAll("/$", "") + "/api/external-jobs/" + jobId + "/callback";
    }

    public boolean timedOut(ExternalJobRow job) {
        if (job.dispatchedAt() == null) {
            return false;
        }
        return job.dispatchedAt().plus(properties.getExternal().getPollMaxDuration()).isBefore(time.nowUtc());
    }

    public void markUnknown(String tenantId, String jobId, String message) {
        jobRepository.updateMessage(tenantId, jobId, message, time.nowUtc());
        jobRepository.updateStatus(tenantId, jobId, "UNKNOWN", null, message, null, time.nowUtc());
    }

    /** Folder used for scanning, verified to stay inside the tenant directory. */
    public Path scanFolder(String tenantId, ExternalJobRow job) {
        Path tenantRoot = dataLayout.tenantRoot(tenantId);
        Path folder = Path.of(job.folderPath()).toAbsolutePath().normalize();
        if (!folder.startsWith(tenantRoot)) {
            throw ApiException.invalid("Job folder is outside the tenant data directory");
        }
        return folder;
    }

    private CsvFormatOptions options(String tenantId, ExternalJobRequest request) {
        return request.options() != null ? request.options() : settings.exportOptions(tenantId);
    }

    private Map<String, Object> dispatchMap(DispatchPayload payload) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("requestId", payload.requestId());
        map.put("productType", payload.productType());
        map.put("workingFolder", payload.workingFolder());
        map.put("inputFile", payload.inputFile());
        map.put("rowCount", payload.rowCount());
        map.put("callbackUrl", payload.callbackUrl());
        return map;
    }
}
