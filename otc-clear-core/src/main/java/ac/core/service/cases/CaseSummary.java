package ac.core.service.cases;

import java.util.List;

/**
 * Compact case representation for tables.
 *
 * @param id                case id
 * @param batchId           owning batch
 * @param productCode       product code
 * @param origin            {@code IMPORT}, {@code CLONE} or {@code EXTERNAL_OUTPUT}
 * @param rowNo             position inside the batch
 * @param testCaseId        test case id
 * @param originalRecordId  original record id
 * @param businessRecordId  business record id
 * @param keyValue          unique field value
 * @param formulaCount      number of captured formulas
 * @param version           optimistic lock version
 * @param preview           a few leading trade fields for the table
 */
public record CaseSummary(
        String id,
        String batchId,
        String productCode,
        String origin,
        int rowNo,
        String testCaseId,
        String originalRecordId,
        String businessRecordId,
        String keyValue,
        int formulaCount,
        int version,
        List<PreviewField> preview) {

    public CaseSummary {
        preview = List.copyOf(preview);
    }

    /**
     * One preview cell.
     *
     * @param header  field header
     * @param value   display value
     * @param formula true when the source cell held a formula
     */
    public record PreviewField(String header, String value, boolean formula) {
    }
}
