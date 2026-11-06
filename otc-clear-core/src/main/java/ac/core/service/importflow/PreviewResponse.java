package ac.core.service.importflow;

import ac.domain.analysis.SheetPreview;

/**
 * Wizard preview response.
 *
 * @param upload      upload summary
 * @param sheetIndex  resolved sheet index
 * @param productCode product used for the preview
 * @param preview     analysed sheet
 */
public record PreviewResponse(UploadView upload, int sheetIndex, String productCode, SheetPreview preview) {
}
