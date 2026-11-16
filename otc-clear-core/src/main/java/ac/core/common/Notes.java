package ac.core.common;

/** Normalisation of the free text remarks shown on the task, batch and export lists. */
public final class Notes {

    /** Maximum number of characters of one remark. */
    public static final int MAX_LENGTH = 2000;

    private Notes() {
    }

    /**
     * Normalises a remark coming from the UI.
     *
     * <p>Line endings are unified so a multi line note survives the round trip, surrounding blanks are
     * dropped and an empty text clears the remark.
     *
     * @param raw remark as typed by the user, {@code null} clears it as well
     * @return the text to store, or {@code null} when the remark is empty
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (text.length() > MAX_LENGTH) {
            throw ApiException.invalid("The remark must be at most " + MAX_LENGTH + " characters");
        }
        return text.isEmpty() ? null : text;
    }
}
