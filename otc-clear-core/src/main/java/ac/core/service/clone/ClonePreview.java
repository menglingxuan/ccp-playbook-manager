package ac.core.service.clone;

import java.util.List;

/**
 * Result of planning a clone run.
 *
 * @param productCode product code
 * @param sourceCount number of source cases
 * @param copies      copies per source case
 * @param totalRows   planned total rows
 * @param sample      first planned rows for the preview panel
 * @param conflicts   duplicate keys that block the clone
 */
public record ClonePreview(
        String productCode,
        int sourceCount,
        int copies,
        int totalRows,
        List<PlannedRow> sample,
        List<String> conflicts) {

    public ClonePreview {
        sample = List.copyOf(sample);
        conflicts = List.copyOf(conflicts);
    }

    /**
     * One planned clone row.
     *
     * @param sourceCaseId      source case id
     * @param sourceTestCaseId  source test case id
     * @param newTestCaseId     new test case id
     * @param newKeyValue       new unique field value
     * @param newOriginalRecordId new original record id
     */
    public record PlannedRow(String sourceCaseId, String sourceTestCaseId, String newTestCaseId,
            String newKeyValue, String newOriginalRecordId) {
    }
}
