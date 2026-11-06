package ac.core.service.external;

/**
 * Payload sent to the external batch import tool.
 *
 * @param requestId     idempotency key, equal to the local job id
 * @param productType   product code
 * @param workingFolder absolute job folder the tool may write to
 * @param inputFile     absolute path of the generated CSV
 * @param rowCount      number of data rows
 * @param callbackUrl   URL the tool may call when finished
 */
public record DispatchPayload(
        String requestId,
        String productType,
        String workingFolder,
        String inputFile,
        int rowCount,
        String callbackUrl) {
}
