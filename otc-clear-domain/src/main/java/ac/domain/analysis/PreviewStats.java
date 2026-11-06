package ac.domain.analysis;

/**
 * Preview statistics.
 *
 * @param dataRows           number of non blank rows below the header
 * @param mappedTradeColumns trade columns bound to a field
 * @param managedColumns     columns outside the trade block
 * @param sampleInvalidRows  invalid rows inside the sampled window
 */
public record PreviewStats(int dataRows, int mappedTradeColumns, int managedColumns, int sampleInvalidRows) {
}
