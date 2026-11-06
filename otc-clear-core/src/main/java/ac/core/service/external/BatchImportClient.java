package ac.core.service.external;

import ac.core.persistence.ExternalJobRow;

/** Client abstraction over the external batch import tool. */
public interface BatchImportClient {

    /** Sends the job and returns the external job id when the tool assigns one. */
    DispatchResult dispatch(ExternalJobRow job, DispatchPayload payload);

    /** Reads the current status; implementations must map unknown values to {@code UNKNOWN}. */
    ExternalStatus status(String externalJobId);

    /** Requests cancellation; returns true when the tool acknowledged it. */
    boolean stop(String externalJobId);

    /** Whether dispatching is possible with the current configuration. */
    boolean enabled();
}
