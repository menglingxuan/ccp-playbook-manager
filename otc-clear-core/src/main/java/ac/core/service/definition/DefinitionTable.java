package ac.core.service.definition;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One exported table: its worksheet name and the columns written in that order.
 *
 * <p>The column names are the ones the export writes and the import expects, so a file produced by
 * one instance can be restored into another.
 */
public enum DefinitionTable {

    /** Global platform list; platforms are shared by every tenant. */
    PLATFORM(DefinitionScope.PRODUCT, "PLATFORM",
            List.of("code", "name", "description", "sort_order", "enabled")),

    BASE_PRODUCT(DefinitionScope.PRODUCT, "BASE_PRODUCT",
            List.of("platform_code", "code", "name", "description", "status", "sort_order", "schema_hash")),

    BASE_FIELD(DefinitionScope.PRODUCT, "BASE_FIELD",
            List.of("platform_code", "base_code", "field_key", "header", "display_name", "aliases",
                    "value_type_code", "role_code", "tags", "required", "business_key", "key_flag",
                    "sort_order", "remark", "format_hint", "clone_pattern", "validation_expr")),

    PRODUCT(DefinitionScope.PRODUCT, "PRODUCT",
            List.of("platform_code", "base_code", "code", "name", "description", "status", "strict_match",
                    "schema_hash")),

    PRODUCT_FIELD(DefinitionScope.PRODUCT, "PRODUCT_FIELD",
            List.of("product_code", "field_key", "header", "display_name", "aliases", "value_type_code",
                    "role_code", "tags", "required", "business_key", "key_flag", "sort_order", "remark",
                    "format_hint", "clone_pattern", "validation_expr")),

    META_SET(DefinitionScope.META, "META_SET",
            List.of("code", "name", "description", "built_in", "sort_order", "type")),

    META_ITEM(DefinitionScope.META, "META_ITEM",
            List.of("set_code", "code", "label", "ref_set_code", "sort_order", "enabled", "remark",
                    "content", "rule_type", "severity"));

    private final DefinitionScope scope;
    private final String sheet;
    private final List<String> columns;

    DefinitionTable(DefinitionScope scope, String sheet, List<String> columns) {
        this.scope = scope;
        this.sheet = sheet;
        this.columns = columns;
    }

    public DefinitionScope scope() {
        return scope;
    }

    /** Worksheet name, also used as the table name of the SQL export. */
    public String sheet() {
        return sheet;
    }

    public List<String> columns() {
        return columns;
    }

    /** Tables of one scope, in the order rows have to be written. */
    public static List<DefinitionTable> byScope(DefinitionScope scope) {
        return Arrays.stream(values()).filter(table -> table.scope == scope).toList();
    }

    /** One table per worksheet name, when a file carries a sheet this application knows. */
    public static Optional<DefinitionTable> bySheet(String sheet) {
        String name = sheet == null ? "" : sheet.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(table -> table.sheet.equals(name)).findFirst();
    }
}
