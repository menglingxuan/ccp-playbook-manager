package ac.core.service.external;

/** Local state machine of an external batch import job. */
public enum ExternalJobStatus {
    PENDING,
    DISPATCHED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    STOPPED,
    UNKNOWN;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == STOPPED;
    }

    public boolean active() {
        return this == PENDING || this == DISPATCHED || this == RUNNING || this == UNKNOWN;
    }

    /** Maps a status string returned by the external tool. Unknown values become {@link #UNKNOWN}. */
    public static ExternalJobStatus fromExternal(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "queued", "pending" -> DISPATCHED;
            case "running", "processing", "in_progress" -> RUNNING;
            case "success", "succeeded", "completed", "done", "finished" -> SUCCEEDED;
            case "failed", "error" -> FAILED;
            case "stopped", "cancelled", "canceled" -> STOPPED;
            default -> UNKNOWN;
        };
    }
}
