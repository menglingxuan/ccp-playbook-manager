package ac.web.api;

import ac.core.common.PageQuery;
import ac.core.service.external.ExternalJobRequest;
import ac.core.service.external.ExternalJobService;
import ac.core.service.external.ExternalOutputService;
import ac.core.service.external.ExternalPrecheck;
import ac.core.service.external.ScanResult;
import ac.core.service.settings.SettingsService;
import ac.web.config.TenantProvider;
import ac.web.dto.ViewMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** External batch import job endpoints. */
@RestController
@RequestMapping("/api/external-jobs")
public class ExternalJobController {

    private final ExternalJobService jobService;
    private final ExternalOutputService outputService;
    private final TenantProvider tenantProvider;

    public ExternalJobController(ExternalJobService jobService, ExternalOutputService outputService,
            TenantProvider tenantProvider) {
        this.jobService = jobService;
        this.outputService = outputService;
        this.tenantProvider = tenantProvider;
    }

    @PostMapping("/precheck")
    public ExternalPrecheck precheck(@RequestBody ExternalJobRequest request) {
        return jobService.precheck(tenantProvider.tenantId(), request);
    }

    @PostMapping
    public Object create(@RequestBody ExternalJobRequest request) {
        return ViewMapper.externalJob(jobService.create(tenantProvider.tenantId(), request));
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String product,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageQuery pageQuery = PageQuery.of(page, size);
        var result = jobService.search(tenantProvider.tenantId(), status, product, query, pageQuery);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.items().stream().map(ViewMapper::externalJob).toList());
        body.put("page", result.page());
        body.put("size", result.size());
        body.put("total", result.total());
        body.put("totalPages", result.totalPages());
        return body;
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable String id) {
        var job = jobService.require(tenantProvider.tenantId(), id);
        Map<String, Object> body = new LinkedHashMap<>(ViewMapper.externalJob(job));
        body.put("outputs", outputService.listByJob(tenantProvider.tenantId(), id).stream()
                .map(output -> ViewMapper.output(output,
                        outputService.caseCount(tenantProvider.tenantId(), output.importedBatchId())))
                .toList());
        return body;
    }

    @PostMapping("/{id}/refresh")
    public Object refresh(@PathVariable String id) {
        return ViewMapper.externalJob(jobService.refresh(tenantProvider.tenantId(), id));
    }

    @PostMapping("/{id}/stop")
    public Object stop(@PathVariable String id) {
        return ViewMapper.externalJob(jobService.stop(tenantProvider.tenantId(), id));
    }

    @PostMapping("/{id}/scan")
    public ScanResult scan(@PathVariable String id) {
        return jobService.scan(tenantProvider.tenantId(), id);
    }

    @PostMapping("/{id}/callback")
    public Map<String, Object> callback(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        String token = body == null ? null : String.valueOf(body.getOrDefault("token", ""));
        String status = body == null ? null : String.valueOf(body.getOrDefault("status", ""));
        String message = body == null ? null : String.valueOf(body.getOrDefault("message", ""));
        jobService.callback(tenantProvider.tenantId(), id, token, status, message);
        return Map.of("accepted", true);
    }

    @GetMapping("/{id}/outputs")
    public Map<String, Object> outputs(@PathVariable String id) {
        return Map.of("items", outputService.listByJob(tenantProvider.tenantId(), id).stream()
                .map(output -> ViewMapper.output(output,
                        outputService.caseCount(tenantProvider.tenantId(), output.importedBatchId())))
                .toList());
    }

    @GetMapping("/{jobId}/files/{outputId}/download")
    public ResponseEntity<Resource> download(@PathVariable String jobId, @PathVariable String outputId)
            throws java.io.IOException {
        var job = jobService.require(tenantProvider.tenantId(), jobId);
        var output = outputService.require(tenantProvider.tenantId(), outputId);
        Path folder = jobService.scanFolder(tenantProvider.tenantId(), job);
        Path file = folder.resolve(output.relativePath()).normalize();
        if (!file.startsWith(folder) || !Files.isRegularFile(file)) {
            throw ac.core.common.ApiException.notFound("Output file is not available");
        }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(output.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(new FileSystemResource(file));
    }
}
