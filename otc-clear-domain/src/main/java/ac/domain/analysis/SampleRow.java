package ac.domain.analysis;

import ac.domain.casefile.CellTexts;
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
        CellTexts rawTexts,
        List<ValidationIssue> issues,
        boolean valid) {

    public SampleRow {
        values = Map.copyOf(values);
        formulas = Map.copyOf(formulas);
        managed = Map.copyOf(managed);
        extras = Map.copyOf(extras);
        rawTexts = rawTexts == null ? CellTexts.EMPTY : rawTexts;
        issues = List.copyOf(issues);
    }

    /** Texts of this row: original cell texts first, typed values as the fallback. */
    public CellTexts texts() {
        return CellTexts.of(values, managed, rawTexts);
    }
}
