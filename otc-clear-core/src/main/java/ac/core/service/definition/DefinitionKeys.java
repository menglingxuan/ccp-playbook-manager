package ac.core.service.definition;

import java.util.ArrayList;
import java.util.List;

/**
 * Business keys of the exported rows.
 *
 * <p>The keys decide what "the same definition" means: an import matches an incoming row against the
 * stored one through these columns, which is how additions, changes and conflicts are recognised.
 */
public final class DefinitionKeys {

    private DefinitionKeys() {
    }

    /** Columns that identify one row of the table. */
    public static List<String> keyColumns(DefinitionTable table) {
        return switch (table) {
            case PLATFORM -> List.of("code");
            case BASE_PRODUCT -> List.of("platform_code", "code");
            case BASE_FIELD -> List.of("platform_code", "base_code", "field_key");
            case PRODUCT -> List.of("code");
            case PRODUCT_FIELD -> List.of("product_code", "field_key");
            case META_SET -> List.of("code");
            case META_ITEM -> List.of("set_code", "code");
        };
    }

    /** Human readable key of one row, e.g. {@code MW/IRS} or {@code VALUE_TYPE/TEXT}. */
    public static String describe(DefinitionTable table, DefinitionBundle.Row row) {
        List<String> parts = new ArrayList<>();
        for (String column : keyColumns(table)) {
            String value = row.get(column);
            parts.add(value == null ? "?" : value);
        }
        return String.join("/", parts);
    }
}
