package ac.web.api;

import ac.core.service.definition.DefinitionExportService;
import ac.core.service.definition.DefinitionImportService;
import ac.core.service.definition.DefinitionScope;
import ac.web.config.TenantProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Backup and restore of the definition catalogue.
 *
 * <p>The same endpoints serve both scopes: {@code PRODUCT} exports the platforms, base products,
 * derived products and their fields, {@code META} exports the meta sets and their values.
 */
@RestController
@RequestMapping("/api/definitions")
public class DefinitionBackupController {

    private final DefinitionExportService exports;
    private final DefinitionImportService imports;
    private final TenantProvider tenantProvider;

    public DefinitionBackupController(DefinitionExportService exports, DefinitionImportService imports,
            TenantProvider tenantProvider) {
        this.exports = exports;
        this.imports = imports;
        this.tenantProvider = tenantProvider;
    }

    /**
     * Downloads the definitions of one scope.
     *
     * @param scope  {@code PRODUCT} or {@code META}
     * @param format {@code xlsx} (restorable) or {@code sql} (replayable backup)
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam String scope,
            @RequestParam(defaultValue = "xlsx") String format) {
        DefinitionExportService.File file =
                exports.export(tenantProvider.tenantId(), DefinitionScope.of(scope), format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-File-Name", java.net.URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8))
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }

    /**
     * Parses an uploaded workbook and classifies its rows; nothing is written.
     *
     * @param scope  {@code PRODUCT} or {@code META}
     * @param file   workbook produced by the export
     */
    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DefinitionImportService.Plan preview(@RequestParam String scope,
            @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ac.core.common.ApiException.invalid("Choose a workbook to import");
        }
        try (InputStream content = file.getInputStream()) {
            return imports.preview(tenantProvider.tenantId(), DefinitionScope.of(scope),
                    file.getOriginalFilename() == null ? "import.xlsx" : file.getOriginalFilename(), content);
        } catch (IOException e) {
            throw ac.core.common.ApiException.invalid("Cannot read the uploaded file: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw ac.core.common.ApiException.invalid(e.getMessage());
        }
    }

    /**
     * Applies a previewed plan.
     *
     * @param planId identifier returned by the preview
     * @param body   {@code {"mode": "MERGE_OVERWRITE" | "MERGE_SKIP" | "FULL_RESTORE"}}
     */
    @PostMapping("/import/{planId}")
    public DefinitionImportService.Result apply(@PathVariable String planId,
            @RequestBody(required = false) Map<String, Object> body) {
        String mode = body == null || body.get("mode") == null ? null : String.valueOf(body.get("mode"));
        return imports.apply(tenantProvider.tenantId(), planId,
                DefinitionImportService.ImportMode.of(mode));
    }

    /** Abandons a previewed plan. */
    @DeleteMapping("/import/{planId}")
    public Map<String, Object> discard(@PathVariable String planId) {
        imports.discard(planId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("discarded", true);
        return body;
    }

    /** Keeps a previewed plan for the panel, e.g. after a page refresh. */
    @GetMapping("/import/{planId}")
    public DefinitionImportService.Plan plan(@PathVariable String planId) {
        return imports.require(planId);
    }
}
