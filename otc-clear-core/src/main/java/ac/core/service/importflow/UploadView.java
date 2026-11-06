package ac.core.service.importflow;

import ac.domain.table.SheetMeta;
import java.util.List;

/**
 * Upload summary returned to the wizard.
 *
 * @param uploadId  upload identifier
 * @param fileName  original file name
 * @param sourceType {@code XLSX}, {@code XLS} or {@code CSV}
 * @param sha256    content hash
 * @param sizeBytes file size
 * @param sheets    sheet metadata
 */
public record UploadView(
        String uploadId,
        String fileName,
        String sourceType,
        String sha256,
        long sizeBytes,
        List<SheetMeta> sheets) {

    public UploadView {
        sheets = List.copyOf(sheets);
    }
}
