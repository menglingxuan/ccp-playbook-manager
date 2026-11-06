package ac.domain.analysis;

/**
 * Wizard parameters. A value of {@code 0} means "detect automatically".
 *
 * @param headerRow        1-based row holding column names
 * @param tradeStartColumn 1-based first column of the trade block
 * @param dataStartRow     1-based first data row
 */
public record PreviewOptions(int headerRow, int tradeStartColumn, int dataStartRow) {

    public static final PreviewOptions AUTO = new PreviewOptions(0, 0, 0);
}
