package ac.domain.analysis;

/**
 * Row scanning rules of a full import.
 *
 * @param stopAtEmptyRow   stop at the first empty row instead of skipping it
 * @param stopAtFirstError stop before the first row that carries import errors, so that row and the ones
 *                         behind it are not imported
 */
public record ImportStopPolicy(boolean stopAtEmptyRow, boolean stopAtFirstError) {

    /** Never stop early: every row of the range is imported. */
    public static final ImportStopPolicy CONTINUE = new ImportStopPolicy(false, false);
}
