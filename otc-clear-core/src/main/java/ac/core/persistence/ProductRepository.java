package ac.core.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access for {@code product} and {@code product_field}. */
@Repository
public class ProductRepository {

    private final JdbcClient jdbc;

    public ProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Concrete product row derived from a base product. */
    public record ProductRow(String id, String tenantId, String platformCode, String baseProductId,
            String baseProductCode, String code, String name, String description, String status,
            boolean strictMatch, String schemaHash, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** Product field row; {@code baseFieldId} links back to the inherited definition. */
    public record ProductFieldRow(String id, String tenantId, String productId, String baseFieldId,
            String fieldKey, String header, String displayName, List<String> aliases, String valueTypeCode,
            String roleCode, List<String> tags, boolean required, boolean businessKey, boolean keyFlag,
            int sortOrder, String remark, String formatHint, String clonePattern) {
    }

    private static final String PRODUCT_COLUMNS = """
            id, tenant_id, platform_code, base_product_id, base_product_code, code, name, description, status,
            strict_match, schema_hash, created_at, updated_at
            """;

    private static final String FIELD_COLUMNS = """
            id, tenant_id, product_id, base_field_id, field_key, header, display_name, aliases,
            value_type_code, role_code, tags, required_flag, business_key, key_flag, sort_order, remark,
            format_hint, clone_pattern
            """;

    public void insert(ProductRow row) {
        jdbc.sql("""
                        INSERT INTO product (id, tenant_id, platform_code, base_product_id, base_product_code, code,
                            name, description, status, strict_match, schema_hash, created_at, updated_at)
                        VALUES (:id, :tenantId, :platformCode, :baseProductId, :baseProductCode, :code, :name,
                            :description, :status, :strictMatch, :schemaHash, :createdAt, :updatedAt)
                        """)
                .param("id", row.id()).param("tenantId", row.tenantId())
                .param("platformCode", row.platformCode()).param("baseProductId", row.baseProductId())
                .param("baseProductCode", row.baseProductCode()).param("code", row.code())
                .param("name", row.name()).param("description", row.description()).param("status", row.status())
                .param("strictMatch", row.strictMatch())
                .param("schemaHash", row.schemaHash()).param("createdAt", row.createdAt())
                .param("updatedAt", row.updatedAt()).update();
    }

    public void update(String id, String name, String description, boolean strictMatch,
            LocalDateTime updatedAt) {
        jdbc.sql("""
                        UPDATE product SET name = :name, description = :description, strict_match = :strictMatch,
                            updated_at = :updatedAt
                        WHERE id = :id
                        """)
                .param("name", name).param("description", description).param("strictMatch", strictMatch)
                .param("updatedAt", updatedAt).param("id", id).update();
    }

    public void updateSchemaHash(String id, String schemaHash, LocalDateTime updatedAt) {
        jdbc.sql("UPDATE product SET schema_hash = :schemaHash, updated_at = :updatedAt WHERE id = :id")
                .param("schemaHash", schemaHash).param("updatedAt", updatedAt).param("id", id).update();
    }

    public void delete(String id) {
        jdbc.sql("DELETE FROM product_field WHERE product_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM product WHERE id = :id").param("id", id).update();
    }

    public Optional<ProductRow> findById(String tenantId, String id) {
        return jdbc.sql("SELECT " + PRODUCT_COLUMNS + " FROM product WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(this::mapProduct).optional();
    }

    public Optional<ProductRow> findByCode(String tenantId, String code) {
        return jdbc.sql("SELECT " + PRODUCT_COLUMNS + """
                         FROM product WHERE tenant_id = :tenantId AND code = :code AND status = 'ACTIVE'
                        """)
                .param("tenantId", tenantId).param("code", code).query(this::mapProduct).optional();
    }

    public List<ProductRow> listByBaseProduct(String tenantId, String baseProductId) {
        return jdbc.sql("SELECT " + PRODUCT_COLUMNS + """
                         FROM product WHERE tenant_id = :tenantId AND base_product_id = :baseProductId
                         ORDER BY code
                        """)
                .param("tenantId", tenantId).param("baseProductId", baseProductId)
                .query(this::mapProduct).list();
    }

    public List<ProductRow> listActive(String tenantId) {
        return jdbc.sql("SELECT " + PRODUCT_COLUMNS + """
                         FROM product WHERE tenant_id = :tenantId AND status = 'ACTIVE'
                         ORDER BY platform_code, base_product_code, code
                        """)
                .param("tenantId", tenantId).query(this::mapProduct).list();
    }

    public List<ProductRow> listAll(String tenantId) {
        return jdbc.sql("SELECT " + PRODUCT_COLUMNS + """
                         FROM product WHERE tenant_id = :tenantId
                         ORDER BY platform_code, base_product_code, code
                        """)
                .param("tenantId", tenantId).query(this::mapProduct).list();
    }

    public long countByBaseProduct(String baseProductId) {
        return jdbc.sql("SELECT COUNT(*) FROM product WHERE base_product_id = :id")
                .param("id", baseProductId).query(Long.class).single();
    }

    public boolean existsByCode(String tenantId, String code) {
        return jdbc.sql("SELECT COUNT(*) FROM product WHERE tenant_id = :tenantId AND code = :code")
                .param("tenantId", tenantId).param("code", code).query(Long.class).single() > 0;
    }

    public void insertField(ProductFieldRow row, LocalDateTime now) {
        jdbc.sql("""
                        INSERT INTO product_field (id, tenant_id, product_id, base_field_id, field_key, header,
                            display_name, aliases, value_type_code, role_code, tags, required_flag, business_key,
                            key_flag, sort_order, remark, format_hint, clone_pattern, created_at, updated_at)
                        VALUES (:id, :tenantId, :productId, :baseFieldId, :fieldKey, :header, :displayName,
                            :aliases, :valueTypeCode, :roleCode, :tags, :required, :businessKey, :keyFlag,
                            :sortOrder, :remark, :formatHint, :clonePattern, :now, :now)
                        """)
                .param("id", row.id()).param("tenantId", row.tenantId()).param("productId", row.productId())
                .param("baseFieldId", row.baseFieldId()).param("fieldKey", row.fieldKey())
                .param("header", row.header()).param("displayName", row.displayName())
                .param("aliases", TagCodec.encodeAliases(row.aliases()))
                .param("valueTypeCode", row.valueTypeCode()).param("roleCode", row.roleCode())
                .param("tags", TagCodec.encode(row.tags())).param("required", row.required())
                .param("businessKey", row.businessKey()).param("keyFlag", row.keyFlag())
                .param("sortOrder", row.sortOrder())
                .param("remark", row.remark()).param("formatHint", row.formatHint())
                .param("clonePattern", row.clonePattern()).param("now", now).update();
    }

    public void updateField(ProductFieldRow row, LocalDateTime updatedAt) {
        jdbc.sql("""
                        UPDATE product_field SET header = :header, display_name = :displayName,
                            aliases = :aliases, value_type_code = :valueTypeCode, role_code = :roleCode,
                            tags = :tags, required_flag = :required, business_key = :businessKey,
                            key_flag = :keyFlag, sort_order = :sortOrder, remark = :remark,
                            format_hint = :formatHint, clone_pattern = :clonePattern, updated_at = :updatedAt
                        WHERE id = :id
                        """)
                .param("header", row.header()).param("displayName", row.displayName())
                .param("aliases", TagCodec.encodeAliases(row.aliases()))
                .param("valueTypeCode", row.valueTypeCode()).param("roleCode", row.roleCode())
                .param("tags", TagCodec.encode(row.tags())).param("required", row.required())
                .param("businessKey", row.businessKey()).param("keyFlag", row.keyFlag())
                .param("sortOrder", row.sortOrder())
                .param("remark", row.remark()).param("formatHint", row.formatHint())
                .param("clonePattern", row.clonePattern()).param("updatedAt", updatedAt)
                .param("id", row.id()).update();
    }

    public void updateFieldOrder(String id, int sortOrder, LocalDateTime updatedAt) {
        jdbc.sql("UPDATE product_field SET sort_order = :sortOrder, updated_at = :updatedAt WHERE id = :id")
                .param("sortOrder", sortOrder).param("updatedAt", updatedAt).param("id", id).update();
    }

    public void deleteField(String id) {
        jdbc.sql("DELETE FROM product_field WHERE id = :id").param("id", id).update();
    }

    /** Removes every product copy of a base field; returns the number of affected rows. */
    public int deleteFieldsByBaseField(String baseFieldId) {
        return jdbc.sql("DELETE FROM product_field WHERE base_field_id = :baseFieldId")
                .param("baseFieldId", baseFieldId).update();
    }

    public List<ProductFieldRow> listFields(String tenantId, String productId) {
        return jdbc.sql("SELECT " + FIELD_COLUMNS + """
                         FROM product_field WHERE tenant_id = :tenantId AND product_id = :productId
                         ORDER BY sort_order, field_key
                        """)
                .param("tenantId", tenantId).param("productId", productId)
                .query(this::mapField).list();
    }

    public Optional<ProductFieldRow> findField(String tenantId, String productId, String fieldId) {
        return jdbc.sql("SELECT " + FIELD_COLUMNS + """
                         FROM product_field
                         WHERE tenant_id = :tenantId AND product_id = :productId AND id = :id
                        """)
                .param("tenantId", tenantId).param("productId", productId).param("id", fieldId)
                .query(this::mapField).optional();
    }

    private ProductRow mapProduct(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProductRow(rs.getString("id"), rs.getString("tenant_id"), rs.getString("platform_code"),
                rs.getString("base_product_id"), rs.getString("base_product_code"), rs.getString("code"),
                rs.getString("name"), rs.getString("description"), rs.getString("status"),
                rs.getBoolean("strict_match"), rs.getString("schema_hash"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    private ProductFieldRow mapField(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProductFieldRow(rs.getString("id"), rs.getString("tenant_id"), rs.getString("product_id"),
                rs.getString("base_field_id"), rs.getString("field_key"), rs.getString("header"),
                rs.getString("display_name"), TagCodec.splitAliases(rs.getString("aliases")),
                rs.getString("value_type_code"), rs.getString("role_code"), TagCodec.decode(rs.getString("tags")),
                rs.getBoolean("required_flag"), rs.getBoolean("business_key"), rs.getBoolean("key_flag"),
                rs.getInt("sort_order"), rs.getString("remark"), rs.getString("format_hint"),
                rs.getString("clone_pattern"));
    }
}
