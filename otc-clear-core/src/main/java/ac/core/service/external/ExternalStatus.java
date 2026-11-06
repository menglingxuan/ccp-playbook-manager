package ac.core.service.external;

/**
 * Snapshot of the external job state.
 *
 * @param raw      raw status text
 * @param mapped   mapped local status
 * @param message  optional message from the tool
 * @param body     raw response body
 */
public record ExternalStatus(String raw, ExternalJobStatus mapped, String message, String body) {

    public static ExternalStatus unknown(String body) {
        return new ExternalStatus(null, ExternalJobStatus.UNKNOWN, "Status could not be determined", body);
    }
}
