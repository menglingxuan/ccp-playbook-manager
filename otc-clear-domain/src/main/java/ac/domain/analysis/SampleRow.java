package ac.domain.analysis;

import ac.domain.casefile.ValidationIssue;
import java.util.List;
import java.util.Map;

/** One converted sample row shown in the wizard preview. */
public record SampleRow(
        int row,
        Map<String, Object> values,
        Map<String, String> formulas,
        Map<String, String> managed,
        Map<String, String> extras,
        List<ValidationIssue> issues,
        boolean valid) {

    public SampleRow {
        values = Map.copyOf(values);
        formulas = Map.copyOf(formulas);
        managed = Map.copyOf(managed);
        extras = Map.copyOf(extras);
        issues = List.copyOf(issues);
    }
}
