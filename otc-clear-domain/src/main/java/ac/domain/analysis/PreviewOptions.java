package ac.domain.analysis;

/**
 * Wizard parameters. A value of {@code 0} means "detect automatically".
 *
 * @param headerRow        1-based row holding column names
 * @param tradeStartColumn 1-based first column of the trade block
 * @param dataStartRow     1-based first data row
 * @param dataEndRow       1-based last data row, {@code 0} means "until the end of the sheet"
 */
public record PreviewOptions(int headerRow, int tradeStartColumn, int dataStartRow, int dataEndRow) {

    public static final PreviewOptions AUTO = new PreviewOptions(0, 0, 0, 0);

    /** Options without an explicit end row, meaning the whole sheet below the data start row. */
    public PreviewOptions(int headerRow, int tradeStartColumn, int dataStartRow) {
        this(headerRow, tradeStartColumn, dataStartRow, 0);
    }
}
