package ac.core.common;

import java.util.UUID;

/** Identifier helpers. */
public final class Ids {

    private Ids() {
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** Short display form of a uuid-ish identifier. */
    public static String shortId(String id) {
        if (id == null) {
            return "";
        }
        int dash = id.indexOf('-');
        return dash > 0 ? id.substring(0, dash) : id.substring(0, Math.min(8, id.length()));
    }
}
