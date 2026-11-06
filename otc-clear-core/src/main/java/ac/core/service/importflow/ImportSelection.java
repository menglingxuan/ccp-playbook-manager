package ac.core.service.importflow;

/**
 * One sheet selected in the import wizard.
 *
 * @param sheetIndex       zero based sheet index
 * @param productCode      product code
 * @param headerRow        1-based header row, 0 or null means auto detect
 * @param tradeStartColumn 1-based trade block start, 0 or null means auto detect
 * @param dataStartRow     1-based first data row, 0 or null means header row plus one
 */
public record ImportSelection(
        Integer sheetIndex,
        String productCode,
        Integer headerRow,
        Integer tradeStartColumn,
        Integer dataStartRow) {

    public int resolvedSheetIndex() {
        return sheetIndex == null || sheetIndex < 0 ? 0 : sheetIndex;
    }

    public int headerRowOrAuto() {
        return headerRow == null ? 0 : headerRow;
    }

    public int tradeStartColumnOrAuto() {
        return tradeStartColumn == null ? 0 : tradeStartColumn;
    }

    public int dataStartRowOrAuto() {
        return dataStartRow == null ? 0 : dataStartRow;
    }
}
