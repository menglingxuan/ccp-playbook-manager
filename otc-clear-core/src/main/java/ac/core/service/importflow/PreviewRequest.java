package ac.core.service.importflow;

/**
 * Wizard preview request.
 *
 * @param sheetIndex       zero based sheet index, {@code null} means 0
 * @param productCode      product code, {@code null} means auto detect
 * @param headerRow        1-based header row, {@code null} or 0 means auto detect
 * @param tradeStartColumn 1-based trade block start, {@code null} or 0 means auto detect
 * @param dataStartRow     1-based first data row, {@code null} or 0 means header row plus one
 * @param dataEndRow       1-based last data row, {@code null} or 0 means "until the end of the sheet"
 * @param sampleLimit      page size of the row preview, {@code null} or 0 means the server default
 * @param sampleOffset     zero based offset of the first row of the page, used by the pager
 */
public record PreviewRequest(
        Integer sheetIndex,
        String productCode,
        Integer headerRow,
        Integer tradeStartColumn,
        Integer dataStartRow,
        Integer dataEndRow,
        Integer sampleLimit,
        Integer sampleOffset) {

    /** Upper bound for one preview page. */
    public static final int MAX_SAMPLE_ROWS = 500;

    public int resolvedSheetIndex() {
        return sheetIndex == null || sheetIndex < 0 ? 0 : sheetIndex;
    }

    /** Requested page size clamped to the readable range; 0 means "server default". */
    public int resolvedSampleLimit() {
        return sampleLimit == null || sampleLimit <= 0 ? 0 : Math.min(sampleLimit, MAX_SAMPLE_ROWS);
    }

    public int resolvedSampleOffset() {
        return sampleOffset == null || sampleOffset < 0 ? 0 : sampleOffset;
    }
}
