package ac.domain.analysis;

import ac.domain.casefile.CaseRow;
import ac.domain.casefile.ValidationIssue;
import ac.domain.layout.SheetLayout;
import java.util.List;

/**
 * Full conversion result of one sheet.
 *
 * @param layout     resolved layout
 * @param rows       converted, non empty rows
 * @param issues     sheet level issues
 * @param dataRows   number of non empty data rows
 * @param validRows  rows without blocking issues
 * @param invalidRows rows with blocking issues
 * @param emptyRows  blank rows that were skipped
 */
public record SheetAnalysis(
        SheetLayout layout,
        List<CaseRow> rows,
        List<ValidationIssue> issues,
        int dataRows,
        int validRows,
        int invalidRows,
        int emptyRows) {

    public SheetAnalysis {
        rows = List.copyOf(rows);
        issues = List.copyOf(issues);
    }
}
