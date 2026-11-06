package ac.domain.table;

/** One raw cell with its display text and optional formula text. */
public record RawCell(String text, String formula, boolean formulaCell, String formulaError) {

    public RawCell {
        text = text == null ? "" : text;
    }

    public static RawCell of(String text) {
        return new RawCell(text, null, false, null);
    }

    public static RawCell formula(String text, String formula, String formulaError) {
        return new RawCell(text, formula, true, formulaError);
    }

    public boolean hasFormula() {
        return formulaCell;
    }

    public boolean hasFormulaError() {
        return formulaError != null;
    }
}
