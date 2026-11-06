package ac.domain.table;

import java.util.Arrays;

/** One row of raw cell data, stored column aligned. */
public record RawRow(String[] texts, String[] formulas, String[] formulaErrors) {

    public RawRow {
        texts = texts == null ? new String[0] : texts;
        formulas = formulas;
        formulaErrors = formulaErrors;
    }

    public static RawRow plain(String[] texts) {
        return new RawRow(texts, null, null);
    }

    public String text(int column) {
        return column >= 0 && column < texts.length && texts[column] != null ? texts[column] : "";
    }

    public String formula(int column) {
        return formulas != null && column >= 0 && column < formulas.length ? formulas[column] : null;
    }

    public String formulaError(int column) {
        return formulaErrors != null && column >= 0 && column < formulaErrors.length
                ? formulaErrors[column] : null;
    }

    public boolean hasFormula(int column) {
        return formula(column) != null;
    }

    public RawCell cell(int column) {
        String formulaText = formula(column);
        String error = formulaError(column);
        if (formulaText == null && error == null) {
            return RawCell.of(text(column));
        }
        return RawCell.formula(text(column), formulaText, error);
    }

    public boolean isBlank() {
        return Arrays.stream(texts).allMatch(value -> value == null || value.isBlank());
    }
}
