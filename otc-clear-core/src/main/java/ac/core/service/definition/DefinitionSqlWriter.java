package ac.core.service.definition;

import ac.core.common.Ids;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes a definition export as replayable SQL.
 *
 * <p>The script clears the rows of the scope for one tenant and re-inserts them with fresh
 * identifiers, so it can be replayed against any H2 database holding that tenant. Platform rows are
 * global, so they are merged by code instead of being deleted.
 */
public final class DefinitionSqlWriter {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private DefinitionSqlWriter() {
    }

    /** Full script for one scope. */
    public static String write(DefinitionBundle bundle, DefinitionScope scope, LocalDateTime now) {
        StringBuilder sql = new StringBuilder(16_384);
        sql.append("-- Tabular definition export (").append(scope.name()).append(", ").append(scope.label())
                .append(")\n");
        sql.append("-- tenant: ").append(bundle.tenantId()).append('\n');
        sql.append("-- generated: ").append(now.format(STAMP)).append('\n');
        sql.append("-- Replay this file with any H2 client connected to the tenant database.\n");
        sql.append("-- Every row keeps its original identifier, so the restored catalogue is identical.\n\n");
        sql.append("SET REFERENTIAL_INTEGRITY FALSE;\n");
        sql.append("BEGIN TRANSACTION;\n\n");

        for (String table : clearedTables(scope)) {
            sql.append("DELETE FROM ").append(table).append(" WHERE tenant_id = ")
                    .append(text(bundle.tenantId())).append(";\n");
        }
        sql.append('\n');

        for (DefinitionTable table : scope.tables()) {
            List<DefinitionBundle.Row> rows = bundle.rows(table);
            if (!rows.isEmpty()) {
                sql.append("-- ").append(table.sheet()).append(": ").append(rows.size()).append(" row(s)\n");
                for (DefinitionBundle.Row row : rows) {
                    sql.append(insert(table, row, bundle.tenantId())).append('\n');
                }
                sql.append('\n');
            }
        }

        sql.append("COMMIT;\n");
        sql.append("SET REFERENTIAL_INTEGRITY TRUE;\n");
        return sql.toString();
    }

    /** Tables of the scope holding tenant data, in delete order. */
    static List<String> clearedTables(DefinitionScope scope) {
        return switch (scope) {
            case PRODUCT -> List.of("product_field", "product", "base_product_field", "base_product");
            case META -> List.of("meta_item", "meta_set");
        };
    }

    private static String insert(DefinitionTable table, DefinitionBundle.Row row, String tenantId) {
        return switch (table) {
            case PLATFORM -> "MERGE INTO platform (code, name, description, sort_order, enabled) KEY (code) VALUES ("
                    + text(row.get("code")) + ", " + text(row.get("name", "")) + ", "
                    + text(row.get("description")) + ", " + number(row.getInt("sort_order", 0)) + ", "
                    + bool(row.getBoolean("enabled", true)) + ");";
            case BASE_PRODUCT -> "INSERT INTO base_product (id, tenant_id, platform_code, code, name, "
                    + "description, status, sort_order, schema_hash, created_at, updated_at) VALUES ("
                    + id() + ", " + text(tenantId) + ", " + text(row.get("platform_code")) + ", "
                    + text(row.get("code")) + ", " + text(row.get("name", "")) + ", "
                    + text(row.get("description")) + ", " + text(row.get("status", "ACTIVE")) + ", "
                    + number(row.getInt("sort_order", 0)) + ", " + text(row.get("schema_hash"))
                    + ", CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);";
            case BASE_FIELD -> "INSERT INTO base_product_field (id, tenant_id, base_product_id, field_key, "
                    + "header, display_name, aliases, value_type_code, role_code, tags, required_flag, "
                    + "business_key, key_flag, sort_order, remark, format_hint, clone_pattern, validation_expr, "
                    + "created_at, updated_at) SELECT " + id() + ", " + text(tenantId) + ", b.id, "
                    + text(row.get("field_key")) + ", " + text(row.get("header", "")) + ", "
                    + text(row.get("display_name")) + ", " + text(row.get("aliases")) + ", "
                    + text(row.get("value_type_code", "TEXT")) + ", " + text(row.get("role_code", "BUSINESS"))
                    + ", " + text(row.get("tags")) + ", " + bool(row.getBoolean("required", false)) + ", "
                    + bool(row.getBoolean("business_key", true)) + ", "
                    + bool(row.getBoolean("key_flag", false)) + ", " + number(row.getInt("sort_order", 0))
                    + ", " + text(row.get("remark")) + ", " + text(row.get("format_hint")) + ", "
                    + text(row.get("clone_pattern")) + ", " + text(row.get("validation_expr"))
                    + ", CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM base_product b WHERE b.tenant_id = "
                    + text(tenantId) + " AND b.platform_code = " + text(row.get("platform_code"))
                    + " AND b.code = " + text(row.get("base_code")) + ";";
            case PRODUCT -> "INSERT INTO product (id, tenant_id, platform_code, base_product_id, "
                    + "base_product_code, code, name, description, status, strict_match, schema_hash, "
                    + "created_at, updated_at) SELECT " + id() + ", " + text(tenantId) + ", "
                    + text(row.get("platform_code")) + ", b.id, " + text(row.get("base_code")) + ", "
                    + text(row.get("code")) + ", " + text(row.get("name", "")) + ", "
                    + text(row.get("description")) + ", " + text(row.get("status", "ACTIVE")) + ", "
                    + bool(row.getBoolean("strict_match", false)) + ", " + text(row.get("schema_hash"))
                    + ", CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM base_product b WHERE b.tenant_id = "
                    + text(tenantId) + " AND b.platform_code = " + text(row.get("platform_code"))
                    + " AND b.code = " + text(row.get("base_code")) + ";";
            case PRODUCT_FIELD -> "INSERT INTO product_field (id, tenant_id, product_id, base_field_id, "
                    + "field_key, header, display_name, aliases, value_type_code, role_code, tags, "
                    + "required_flag, business_key, key_flag, sort_order, remark, format_hint, clone_pattern, "
                    + "validation_expr, created_at, updated_at) SELECT " + id() + ", " + text(tenantId)
                    + ", p.id, (SELECT f.id FROM base_product_field f WHERE f.base_product_id = "
                    + "p.base_product_id AND f.field_key = " + text(row.get("field_key"))
                    + " FETCH FIRST 1 ROWS ONLY), " + text(row.get("field_key")) + ", "
                    + text(row.get("header", "")) + ", " + text(row.get("display_name")) + ", "
                    + text(row.get("aliases")) + ", " + text(row.get("value_type_code", "TEXT")) + ", "
                    + text(row.get("role_code", "BUSINESS")) + ", " + text(row.get("tags")) + ", "
                    + bool(row.getBoolean("required", false)) + ", "
                    + bool(row.getBoolean("business_key", true)) + ", "
                    + bool(row.getBoolean("key_flag", false)) + ", " + number(row.getInt("sort_order", 0))
                    + ", " + text(row.get("remark")) + ", " + text(row.get("format_hint")) + ", "
                    + text(row.get("clone_pattern")) + ", " + text(row.get("validation_expr"))
                    + ", CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM product p WHERE p.tenant_id = "
                    + text(tenantId) + " AND p.code = " + text(row.get("product_code")) + ";";
            case META_SET -> "INSERT INTO meta_set (tenant_id, code, name, description, built_in, "
                    + "sort_order, set_type, created_at, updated_at) VALUES (" + text(tenantId) + ", "
                    + text(row.get("code")) + ", " + text(row.get("name", "")) + ", "
                    + text(row.get("description")) + ", " + bool(row.getBoolean("built_in", false)) + ", "
                    + number(row.getInt("sort_order", 0)) + ", " + text(row.get("type", "VALUE"))
                    + ", CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);";
            case META_ITEM -> "INSERT INTO meta_item (tenant_id, set_code, code, label, ref_set_code, "
                    + "sort_order, enabled, remark, content, rule_type, severity, created_at, updated_at) VALUES ("
                    + text(tenantId) + ", " + text(row.get("set_code")) + ", " + text(row.get("code")) + ", "
                    + text(row.get("label", "")) + ", " + text(row.get("ref_set_code")) + ", "
                    + number(row.getInt("sort_order", 0)) + ", " + bool(row.getBoolean("enabled", true)) + ", "
                    + text(row.get("remark")) + ", " + text(row.get("content")) + ", "
                    + text(row.get("rule_type")) + ", " + text(row.get("severity", "WARN"))
                    + ", CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);";
        };
    }

    private static String id() {
        return text(Ids.newId());
    }

    /** Quoted literal, or {@code NULL} when there is nothing to write. */
    static String text(String value) {
        return value != null && !value.isEmpty() ? "'" + value.replace("'", "''") + "'" : "NULL";
    }

    private static String number(int value) {
        return Integer.toString(value);
    }

    private static String bool(boolean value) {
        return value ? "TRUE" : "FALSE";
    }
}
