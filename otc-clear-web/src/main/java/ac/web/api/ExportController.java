package ac.web.api;

import ac.core.common.PageQuery;
import ac.core.service.export.ExportPrecheck;
import ac.core.service.export.ExportRequest;
import ac.core.service.export.ExportResult;
import ac.core.service.export.ExportService;
import ac.web.config.TenantProvider;
import ac.web.dto.ViewMapper;
import java.nio.charset.StandardCharsets;
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

/** CSV export endpoints. */
@RestController
@RequestMapping("/api/exports")
public class ExportController {

    private final ExportService exportService;
    private final TenantProvider tenantProvider;

    public ExportController(ExportService exportService, TenantProvider tenantProvider) {
        this.exportService = exportService;
        this.tenantProvider = tenantProvider;
    }

    @PostMapping("/precheck")
    public ExportPrecheck precheck(@RequestBody ExportRequest request) {
        return exportService.precheck(tenantProvider.tenantId(), request);
    }

    @PostMapping
    public ExportResult export(@RequestBody ExportRequest request) {
        return exportService.export(tenantProvider.tenantId(), request);
    }

    @GetMapping
    public Map<String, Object> history(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageQuery pageQuery = PageQuery.of(page, size);
        var result = exportService.history(tenantProvider.tenantId(), pageQuery);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.items().stream().map(ViewMapper::exportJob).toList());
        body.put("page", result.page());
        body.put("size", result.size());
        body.put("total", result.total());
        body.put("totalPages", result.totalPages());
        return body;
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable String id) {
        var job = exportService.requireJob(tenantProvider.tenantId(), id);
        Path path = exportService.requireFile(tenantProvider.tenantId(), id);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(job.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(new FileSystemResource(path));
    }
}
