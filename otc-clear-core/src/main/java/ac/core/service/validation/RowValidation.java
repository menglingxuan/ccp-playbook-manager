package ac.core.service.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One row checked by the rules, as used by the import preview and by the import commit.
 *
 * @param rowNo    source row number
 * @param failures failing fields, empty when every ruled field passed
 */
public record RowValidation(int rowNo, List<FieldRules.Failure> failures) {

    public RowValidation {
        failures = List.copyOf(failures);
    }

    /** Groups the results by row number so callers can merge them into their own row models. */
    public static Map<Integer, List<FieldRules.Failure>> byRow(List<RowValidation> results) {
        Map<Integer, List<FieldRules.Failure>> byRow = new LinkedHashMap<>();
        for (RowValidation result : results) {
            if (!result.failures().isEmpty()) {
                byRow.computeIfAbsent(result.rowNo(), key -> new ArrayList<>()).addAll(result.failures());
            }
        }
        return byRow;
    }
}
