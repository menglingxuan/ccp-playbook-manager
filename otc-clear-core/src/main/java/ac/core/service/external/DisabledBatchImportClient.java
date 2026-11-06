package ac.core.service.external;

import ac.core.common.ApiException;
import ac.core.common.ErrorCode;
import ac.core.persistence.ExternalJobRow;

/** Used when {@code app.external.enabled=false}; keeps the rest of the flow testable. */
public class DisabledBatchImportClient implements BatchImportClient {

    @Override
    public DispatchResult dispatch(ExternalJobRow job, DispatchPayload payload) {
        throw new ApiException(ErrorCode.EXTERNAL_UNAVAILABLE,
                "The external batch import tool is disabled (app.external.enabled=false)");
    }

    @Override
    public ExternalStatus status(String externalJobId) {
        return ExternalStatus.unknown("external tool disabled");
    }

    @Override
    public boolean stop(String externalJobId) {
        return false;
    }

    @Override
    public boolean enabled() {
        return false;
    }
}
