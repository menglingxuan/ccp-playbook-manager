package ac.core.service.importflow;

/**
 * Wizard preview request.
 *
 * @param sheetIndex       zero based sheet index, {@code null} means 0
 * @param productCode      product code, {@code null} means auto detect
 * @param headerRow        1-based header row, {@code null} or 0 means auto detect
 * @param tradeStartColumn 1-based trade block start, {@code null} or 0 means auto detect
 * @param dataStartRow     1-based first data row, {@code null} or 0 means header row plus one
 */
public record PreviewRequest(
        Integer sheetIndex,
        String productCode,
        Integer headerRow,
        Integer tradeStartColumn,
        Integer dataStartRow) {

    public int resolvedSheetIndex() {
        return sheetIndex == null || sheetIndex < 0 ? 0 : sheetIndex;
    }
}
