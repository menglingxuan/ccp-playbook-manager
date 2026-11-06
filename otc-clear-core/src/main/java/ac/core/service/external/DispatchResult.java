package ac.core.service.external;

/**
 * Result of a dispatch call.
 *
 * @param externalJobId id assigned by the external tool, may be {@code null}
 * @param rawResponse   raw response body for troubleshooting
 */
public record DispatchResult(String externalJobId, String rawResponse) {
}
