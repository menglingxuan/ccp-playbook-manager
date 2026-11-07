package ac.core.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access for {@code base_product} and {@code base_product_field}. */
@Repository
public class BaseProductRepository {

    private final JdbcClient jdbc;

    public BaseProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Base product row. */
    public record BaseProductRow(String id, String tenantId, String platformCode, String code, String name,
            String description, String status, int sortOrder, String schemaHash, LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /** Base product field row. */
    public record BaseFieldRow(String id, String tenantId, String baseProductId, String fieldKey, String header,
            String displayName, List<String> aliases, String valueTypeCode, String roleCode, List<String> tags,
            boolean required, boolean businessKey, boolean keyFlag, int sortOrder, String remark,
            String formatHint, String clonePattern) {
    }

    private static final String BASE_COLUMNS = """
            id, tenant_id, platform_code, code, name, description, status, sort_order, schema_hash,
            created_at, updated_at
            """;

    private static final String FIELD_COLUMNS = """
            id, tenant_id, base_product_id, field_key, header, display_name, aliases, value_type_code,
            role_code, tags, required_flag, business_key, key_flag, sort_order, remark, format_hint,
            clone_pattern
            """;

    public void insert(BaseProductRow row) {
        jdbc.sql("""
                        INSERT INTO base_product (id, tenant_id, platform_code, code, name, description, status,
                            sort_order, schema_hash, created_at, updated_at)
                        VALUES (:id, :tenantId, :platformCode, :code, :name, :description, :status,
                            :sortOrder, :schemaHash, :createdAt, :updatedAt)
                        """)
                .param("id", row.id()).param("tenantId", row.tenantId())
                .param("platformCode", row.platformCode()).param("code", row.code()).param("name", row.name())
                .param("description", row.description()).param("status", row.status())
                .param("sortOrder", row.sortOrder()).param("schemaHash", row.schemaHash())
                .param("createdAt", row.createdAt()).param("updatedAt", row.updatedAt()).update();
    }

    public void update(String id, String name, String description, int sortOrder, LocalDateTime updatedAt) {
        jdbc.sql("""
                        UPDATE base_product SET name = :name, description = :description, sort_order = :sortOrder,
                            updated_at = :updatedAt
                        WHERE id = :id
                        """)
                .param("name", name).param("description", description).param("sortOrder", sortOrder)
                .param("updatedAt", updatedAt).param("id", id).update();
    }

    public void updateSchemaHash(String id, String schemaHash, LocalDateTime updatedAt) {
        jdbc.sql("UPDATE base_product SET schema_hash = :schemaHash, updated_at = :updatedAt WHERE id = :id")
                .param("schemaHash", schemaHash).param("updatedAt", updatedAt).param("id", id).update();
    }

    public void delete(String id) {
        jdbc.sql("DELETE FROM base_product_field WHERE base_product_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM base_product WHERE id = :id").param("id", id).update();
    }

    public Optional<BaseProductRow> findById(String tenantId, String id) {
        return jdbc.sql("SELECT " + BASE_COLUMNS + " FROM base_product WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(this::mapBase).optional();
    }

    public Optional<BaseProductRow> findByCode(String tenantId, String platformCode, String code) {
        return jdbc.sql("SELECT " + BASE_COLUMNS + """
                         FROM base_product
                         WHERE tenant_id = :tenantId AND platform_code = :platformCode AND code = :code
                        """)
                .param("tenantId", tenantId).param("platformCode", platformCode).param("code", code)
                .query(this::mapBase).optional();
    }

    public List<BaseProductRow> listByPlatform(String tenantId, String platformCode) {
        return jdbc.sql("SELECT " + BASE_COLUMNS + """
                         FROM base_product
                         WHERE tenant_id = :tenantId AND platform_code = :platformCode
                         ORDER BY sort_order, code
                        """)
                .param("tenantId", tenantId).param("platformCode", platformCode)
                .query(this::mapBase).list();
    }

    public List<BaseProductRow> listAll(String tenantId) {
        return jdbc.sql("SELECT " + BASE_COLUMNS + """
                         FROM base_product WHERE tenant_id = :tenantId ORDER BY platform_code, sort_order, code
                        """)
                .param("tenantId", tenantId).query(this::mapBase).list();
    }

    public long countByPlatform(String tenantId, String platformCode) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM base_product
                        WHERE tenant_id = :tenantId AND platform_code = :platformCode
                        """)
                .param("tenantId", tenantId).param("platformCode", platformCode)
                .query(Long.class).single();
    }

    public void insertField(BaseFieldRow row) {
        jdbc.sql("""
                        INSERT INTO base_product_field (id, tenant_id, base_product_id, field_key, header,
                            display_name, aliases, value_type_code, role_code, tags, required_flag, business_key,
                            key_flag, sort_order, remark, format_hint, clone_pattern, created_at, updated_at)
                        VALUES (:id, :tenantId, :baseProductId, :fieldKey, :header, :displayName, :aliases,
                            :valueTypeCode, :roleCode, :tags, :required, :businessKey, :keyFlag, :sortOrder,
                            :remark, :formatHint, :clonePattern, :now, :now)
                        """)
                .param("id", row.id()).param("tenantId", row.tenantId())
                .param("baseProductId", row.baseProductId()).param("fieldKey", row.fieldKey())
                .param("header", row.header()).param("displayName", row.displayName())
                .param("aliases", TagCodec.encodeAliases(row.aliases()))
                .param("valueTypeCode", row.valueTypeCode()).param("roleCode", row.roleCode())
                .param("tags", TagCodec.encode(row.tags())).param("required", row.required())
                .param("businessKey", row.businessKey()).param("keyFlag", row.keyFlag())
                .param("sortOrder", row.sortOrder())
                .param("remark", row.remark()).param("formatHint", row.formatHint())
                .param("clonePattern", row.clonePattern())
                .param("now", LocalDateTime.now(java.time.ZoneOffset.UTC)).update();
    }

    public void updateField(BaseFieldRow row, LocalDateTime updatedAt) {
        jdbc.sql("""
                        UPDATE base_product_field SET field_key = :fieldKey, header = :header,
                            display_name = :displayName, aliases = :aliases, value_type_code = :valueTypeCode,
                            role_code = :roleCode, tags = :tags, required_flag = :required,
                            business_key = :businessKey, key_flag = :keyFlag, sort_order = :sortOrder,
                            remark = :remark, format_hint = :formatHint, clone_pattern = :clonePattern,
                            updated_at = :updatedAt
                        WHERE id = :id
                        """)
                .param("fieldKey", row.fieldKey()).param("header", row.header())
                .param("displayName", row.displayName()).param("aliases", TagCodec.encodeAliases(row.aliases()))
                .param("valueTypeCode", row.valueTypeCode()).param("roleCode", row.roleCode())
                .param("tags", TagCodec.encode(row.tags())).param("required", row.required())
                .param("businessKey", row.businessKey()).param("keyFlag", row.keyFlag())
                .param("sortOrder", row.sortOrder())
                .param("remark", row.remark()).param("formatHint", row.formatHint())
                .param("clonePattern", row.clonePattern()).param("updatedAt", updatedAt)
                .param("id", row.id()).update();
    }

    public void updateFieldOrder(String id, int sortOrder, LocalDateTime updatedAt) {
        jdbc.sql("UPDATE base_product_field SET sort_order = :sortOrder, updated_at = :updatedAt WHERE id = :id")
                .param("sortOrder", sortOrder).param("updatedAt", updatedAt).param("id", id).update();
    }

    public void deleteField(String id) {
        jdbc.sql("DELETE FROM base_product_field WHERE id = :id").param("id", id).update();
    }

    public List<BaseFieldRow> listFields(String tenantId, String baseProductId) {
        return jdbc.sql("SELECT " + FIELD_COLUMNS + """
                         FROM base_product_field
                         WHERE tenant_id = :tenantId AND base_product_id = :baseProductId
                         ORDER BY sort_order, field_key
                        """)
                .param("tenantId", tenantId).param("baseProductId", baseProductId)
                .query(this::mapField).list();
    }

    public Optional<BaseFieldRow> findField(String tenantId, String baseProductId, String fieldId) {
        return jdbc.sql("SELECT " + FIELD_COLUMNS + """
                         FROM base_product_field
                         WHERE tenant_id = :tenantId AND base_product_id = :baseProductId AND id = :id
                        """)
                .param("tenantId", tenantId).param("baseProductId", baseProductId).param("id", fieldId)
                .query(this::mapField).optional();
    }

    public boolean fieldKeyExists(String baseProductId, String fieldKey) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM base_product_field
                        WHERE base_product_id = :baseProductId AND field_key = :fieldKey
                        """)
                .param("baseProductId", baseProductId).param("fieldKey", fieldKey)
                .query(Long.class).single() > 0;
    }

    public int maxFieldOrder(String baseProductId) {
        Integer max = jdbc.sql("SELECT COALESCE(MAX(sort_order), 0) FROM base_product_field WHERE base_product_id = :id")
                .param("id", baseProductId).query(Integer.class).single();
        return max == null ? 0 : max;
    }

    private BaseProductRow mapBase(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new BaseProductRow(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("platform_code"), rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getString("status"), rs.getInt("sort_order"),
                rs.getString("schema_hash"), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private BaseFieldRow mapField(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new BaseFieldRow(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("base_product_id"), rs.getString("field_key"), rs.getString("header"),
                rs.getString("display_name"), TagCodec.splitAliases(rs.getString("aliases")),
                rs.getString("value_type_code"), rs.getString("role_code"), TagCodec.decode(rs.getString("tags")),
                rs.getBoolean("required_flag"), rs.getBoolean("business_key"), rs.getBoolean("key_flag"),
                rs.getInt("sort_order"), rs.getString("remark"), rs.getString("format_hint"),
                rs.getString("clone_pattern"));
    }
}
