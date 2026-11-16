package ac.web.api;

import ac.core.common.ApiException;
import ac.core.common.ErrorCode;
import ac.core.service.importflow.ImportCommitRequest;
import ac.core.service.importflow.ImportResult;
import ac.core.service.importflow.ImportService;
import ac.core.service.importflow.PreviewRequest;
import ac.core.service.importflow.PreviewResponse;
import ac.core.service.importflow.PreviewService;
import ac.core.service.importflow.StoredUpload;
import ac.core.service.importflow.UploadStore;
import ac.core.service.importflow.UploadView;
import ac.web.config.TenantProvider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Upload, preview and import endpoints used by the wizard. */
@RestController
@RequestMapping("/api")
public class ImportController {

    private final UploadStore uploadStore;
    private final PreviewService previewService;
    private final ImportService importService;
    private final TenantProvider tenantProvider;

    public ImportController(UploadStore uploadStore, PreviewService previewService,
            ImportService importService, TenantProvider tenantProvider) {
        this.uploadStore = uploadStore;
        this.previewService = previewService;
        this.importService = importService;
        this.tenantProvider = tenantProvider;
    }

    @PostMapping("/uploads")
    public UploadView upload(@RequestParam("file") MultipartFile file) {
        String tenantId = tenantProvider.tenantId();
        String fileName = file.getOriginalFilename();
        try {
            if (file.isEmpty()) {
                throw ApiException.invalid("The uploaded file is empty");
            }
            StoredUpload stored = uploadStore.save(tenantId, fileName, file.getInputStream(), file.getSize());
            return previewService.describe(tenantId, stored.uploadId());
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "The uploaded file could not be read");
        }
    }

    @GetMapping("/uploads/{uploadId}")
    public UploadView describe(@PathVariable String uploadId) {
        return previewService.describe(tenantProvider.tenantId(), uploadId);
    }

    @PostMapping("/uploads/{uploadId}/preview")
    public PreviewResponse preview(@PathVariable String uploadId, @RequestBody PreviewRequest request) {
        return previewService.preview(tenantProvider.tenantId(), uploadId, request);
    }

    /**
     * One page of converted rows, used by the wizard pager to browse a whole file.
     *
     * @param uploadId upload identifier
     * @param request  sheet, product, layout parameters and the page offset
     */
    @PostMapping("/uploads/{uploadId}/preview/rows")
    public PreviewService.PreviewPage previewRows(@PathVariable String uploadId,
            @RequestBody PreviewRequest request) {
        return previewService.previewPage(tenantProvider.tenantId(), uploadId, request);
    }

    @GetMapping("/uploads/{uploadId}/sheets/{sheetIndex}/raw")
    public Map<String, Object> raw(@PathVariable String uploadId, @PathVariable int sheetIndex,
            @RequestParam(defaultValue = "20") int rows,
            @RequestParam(defaultValue = "0") int offset) {
        var table = previewService.rawSheetPage(tenantProvider.tenantId(), uploadId, sheetIndex, offset, rows);
        List<Map<String, Object>> data = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < table.rowCount(); rowIndex++) {
            var row = table.row(rowIndex);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("row", offset + rowIndex + 1);
            item.put("values", List.of(row.texts() == null ? new String[0] : row.texts()));
            List<String> formulas = new ArrayList<>();
            for (int column = 0; column < table.columnCount(); column++) {
                formulas.add(row.formula(column));
            }
            item.put("formulas", formulas);
            data.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sheetName", table.sheetName());
        body.put("rows", data);
        body.put("totalRows", table.declaredRowCount());
        body.put("offset", offset);
        return body;
    }

    @PostMapping("/imports")
    public ImportResult commit(@RequestBody ImportCommitRequest request) {
        return importService.commit(tenantProvider.tenantId(), request);
    }
}
