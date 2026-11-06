package ac.core.persistence;

import java.time.LocalDateTime;

/** Persisted test case row. */
public record TestCaseRecord(
        String id,
        String tenantId,
        String batchId,
        String productCode,
        String origin,
        int rowNo,
        Integer sourceRow,
        String testCaseId,
        String testCaseIdSource,
        String originalRecordId,
        String businessRecordId,
        String keyValue,
        String dataJson,
        String managedJson,
        String formulaJson,
        String extrasJson,
        int formulaCount,
        String searchText,
        String status,
        int version,
        String clonedFrom,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
