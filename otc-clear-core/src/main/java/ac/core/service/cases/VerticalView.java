package ac.core.service.cases;

import java.util.List;

/**
 * Vertical (field per line) view of one case.
 *
 * @param caseId      case id
 * @param testCaseId  test case id
 * @param productCode product code
 * @param rowNo       position inside the batch
 * @param rowCount    number of active cases in the batch, for prev/next navigation
 * @param sections    grouped field rows
 */
public record VerticalView(
        String caseId,
        String testCaseId,
        String productCode,
        int rowNo,
        int rowCount,
        List<VerticalSection> sections) {

    public VerticalView {
        sections = List.copyOf(sections);
    }

    /**
     * One group of rows.
     *
     * @param code  stable section code
     * @param title section title
     * @param rows  field rows
     */
    public record VerticalSection(String code, String title, List<VerticalRow> rows) {

        public VerticalSection {
            rows = List.copyOf(rows);
        }
    }

    /**
     * One field row.
     *
     * @param fieldKey field key or managed key
     * @param header   display header
     * @param value    display value, empty string when blank
     * @param formula  formula text when the source cell had one
     * @param type     field type name
     * @param key      true for the product unique field
     */
    public record VerticalRow(String fieldKey, String header, String value, String formula, String type,
            boolean key) {
    }
}
