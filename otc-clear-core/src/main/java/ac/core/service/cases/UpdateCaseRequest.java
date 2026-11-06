package ac.core.service.cases;

import java.util.Map;

/**
 * Case edit request.
 *
 * @param version expected optimistic lock version
 * @param values  trade field key to new raw text value; {@code ""} clears the value
 * @param managed managed key to new text value, may be {@code null}
 */
public record UpdateCaseRequest(int version, Map<String, String> values, Map<String, String> managed) {

    public UpdateCaseRequest {
        values = values == null ? Map.of() : Map.copyOf(values);
        managed = managed == null ? Map.of() : Map.copyOf(managed);
    }
}
