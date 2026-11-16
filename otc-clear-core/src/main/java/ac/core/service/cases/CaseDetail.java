package ac.core.service.cases;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Full case payload used by the detail and edit views.
 *
 * @param data               field key to normalized value (date, time, date time, boolean, number only)
 * @param values             field key to the original text shown on every screen
 * @param managed            managed key to text
 * @param formulas           trade field key to formula text
 * @param managedFormulas    managed key to formula text
 * @param unresolvedFormulas formula cells without capturable text
 * @param extras             unmapped column header to text
 */
public record CaseDetail(
        String id,
        String batchId,
        String productCode,
        String origin,
        int rowNo,
        Integer sourceRow,
        String testCaseId,
        String testCaseIdSource,
        String originalRecordId,
        String businessRecordId,
        String coreBusinessRecordId,
        String keyValue,
        int version,
        Map<String, Object> data,
        Map<String, String> values,
        Map<String, String> managed,
        Map<String, String> formulas,
        Map<String, String> managedFormulas,
        List<Map<String, Object>> unresolvedFormulas,
        Map<String, String> extras,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
