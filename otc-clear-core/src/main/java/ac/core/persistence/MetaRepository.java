package ac.core.persistence;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access for the platform catalogue and the field meta information. */
@Repository
public class MetaRepository {

    private final JdbcClient jdbc;

    public MetaRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Platform reference row. */
    public record PlatformRow(String code, String name, String description, int sortOrder, boolean enabled) {
    }

    /** Meta set row; {@code setType} is {@code VALUE}, {@code FORMULA}, {@code TAG} or {@code VALIDATION}. */
    public record MetaSetRow(String code, String name, String description, boolean builtIn, int sortOrder,
            String setType) {
    }

    /**
     * Meta item row; {@code refSetCode} turns the item into a reference to another set,
     * {@code content} holds the value text (VALUE sets), the formula (FORMULA sets) or the pattern
     * (VALIDATION sets), {@code ruleType} is {@code REGEX} or {@code WILDCARD} for that pattern and
     * {@code severity} is {@code WARN} or {@code ERROR} for a rule that rejects a value.
     */
    public record MetaItemRow(String setCode, String code, String label, String refSetCode, int sortOrder,
            boolean enabled, String remark, String content, String ruleType, String severity) {
    }

    public void upsertPlatform(PlatformRow row) {
        int updated = jdbc.sql("""
                        UPDATE platform SET name = :name, description = :description,
                            sort_order = :sortOrder, enabled = :enabled
                        WHERE code = :code
                        """)
                .param("name", row.name()).param("description", row.description())
                .param("sortOrder", row.sortOrder()).param("enabled", row.enabled())
                .param("code", row.code()).update();
        if (updated == 0) {
            jdbc.sql("""
                            INSERT INTO platform (code, name, description, sort_order, enabled)
                            VALUES (:code, :name, :description, :sortOrder, :enabled)
                            """)
                    .param("code", row.code()).param("name", row.name())
                    .param("description", row.description()).param("sortOrder", row.sortOrder())
                    .param("enabled", row.enabled()).update();
        }
    }

    public List<PlatformRow> platforms() {
        return jdbc.sql("SELECT code, name, description, sort_order, enabled FROM platform ORDER BY sort_order, code")
                .query((rs, rowNum) -> new PlatformRow(rs.getString("code"), rs.getString("name"),
                        rs.getString("description"), rs.getInt("sort_order"), rs.getBoolean("enabled")))
                .list();
    }

    public Optional<PlatformRow> platform(String code) {
        return jdbc.sql("SELECT code, name, description, sort_order, enabled FROM platform WHERE code = :code")
                .param("code", code)
                .query((rs, rowNum) -> new PlatformRow(rs.getString("code"), rs.getString("name"),
                        rs.getString("description"), rs.getInt("sort_order"), rs.getBoolean("enabled")))
                .optional();
    }

    public void insertSet(String tenantId, MetaSetRow row, LocalDateTime now) {
        jdbc.sql("""
                        INSERT INTO meta_set (tenant_id, code, name, description, built_in, sort_order, set_type,
                            created_at, updated_at)
                        VALUES (:tenantId, :code, :name, :description, :builtIn, :sortOrder, :setType, :now, :now)
                        """)
                .param("tenantId", tenantId).param("code", row.code()).param("name", row.name())
                .param("description", row.description()).param("builtIn", row.builtIn())
                .param("sortOrder", row.sortOrder()).param("setType", row.setType())
                .param("now", now).update();
    }

    public void updateSet(String tenantId, String code, String name, String description, int sortOrder,
            String setType, LocalDateTime now) {
        jdbc.sql("""
                        UPDATE meta_set SET name = :name, description = :description, sort_order = :sortOrder,
                            set_type = :setType, updated_at = :now
                        WHERE tenant_id = :tenantId AND code = :code
                        """)
                .param("name", name).param("description", description).param("sortOrder", sortOrder)
                .param("setType", setType).param("now", now)
                .param("tenantId", tenantId).param("code", code).update();
    }

    public void deleteSet(String tenantId, String code) {
        jdbc.sql("DELETE FROM meta_item WHERE tenant_id = :tenantId AND set_code = :code")
                .param("tenantId", tenantId).param("code", code).update();
        jdbc.sql("DELETE FROM meta_set WHERE tenant_id = :tenantId AND code = :code")
                .param("tenantId", tenantId).param("code", code).update();
    }

    public List<MetaSetRow> sets(String tenantId) {
        return jdbc.sql("""
                        SELECT code, name, description, built_in, sort_order, set_type FROM meta_set
                        WHERE tenant_id = :tenantId ORDER BY sort_order, code
                        """)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> new MetaSetRow(rs.getString("code"), rs.getString("name"),
                        rs.getString("description"), rs.getBoolean("built_in"), rs.getInt("sort_order"),
                        rs.getString("set_type")))
                .list();
    }

    public Optional<MetaSetRow> set(String tenantId, String code) {
        return jdbc.sql("""
                        SELECT code, name, description, built_in, sort_order, set_type FROM meta_set
                        WHERE tenant_id = :tenantId AND code = :code
                        """)
                .param("tenantId", tenantId).param("code", code)
                .query((rs, rowNum) -> new MetaSetRow(rs.getString("code"), rs.getString("name"),
                        rs.getString("description"), rs.getBoolean("built_in"), rs.getInt("sort_order"),
                        rs.getString("set_type")))
                .optional();
    }

    public boolean setExists(String tenantId, String code) {
        return jdbc.sql("SELECT COUNT(*) FROM meta_set WHERE tenant_id = :tenantId AND code = :code")
                .param("tenantId", tenantId).param("code", code).query(Long.class).single() > 0;
    }

    public void insertItem(String tenantId, MetaItemRow row, LocalDateTime now) {
        jdbc.sql("""
                        INSERT INTO meta_item (tenant_id, set_code, code, label, ref_set_code, sort_order,
                            enabled, remark, content, rule_type, severity, created_at, updated_at)
                        VALUES (:tenantId, :setCode, :code, :label, :refSetCode, :sortOrder, :enabled,
                            :remark, :content, :ruleType, :severity, :now, :now)
                        """)
                .param("tenantId", tenantId).param("setCode", row.setCode()).param("code", row.code())
                .param("label", row.label()).param("refSetCode", row.refSetCode())
                .param("sortOrder", row.sortOrder()).param("enabled", row.enabled())
                .param("remark", row.remark()).param("content", row.content())
                .param("ruleType", row.ruleType())
                .param("severity", row.severity() == null ? "WARN" : row.severity())
                .param("now", now).update();
    }

    public void updateItem(String tenantId, String setCode, String code, String label, String refSetCode,
            int sortOrder, boolean enabled, String remark, String content, String ruleType,
            String severity, LocalDateTime now) {
        jdbc.sql("""
                        UPDATE meta_item SET label = :label, ref_set_code = :refSetCode, sort_order = :sortOrder,
                            enabled = :enabled, remark = :remark, content = :content, rule_type = :ruleType,
                            severity = :severity, updated_at = :now
                        WHERE tenant_id = :tenantId AND set_code = :setCode AND code = :code
                        """)
                .param("label", label).param("refSetCode", refSetCode).param("sortOrder", sortOrder)
                .param("enabled", enabled).param("remark", remark).param("content", content)
                .param("ruleType", ruleType).param("severity", severity)
                .param("now", now).param("tenantId", tenantId).param("setCode", setCode)
                .param("code", code).update();
    }

    public void deleteItem(String tenantId, String setCode, String code) {
        jdbc.sql("DELETE FROM meta_item WHERE tenant_id = :tenantId AND set_code = :setCode AND code = :code")
                .param("tenantId", tenantId).param("setCode", setCode).param("code", code).update();
    }

    public List<MetaItemRow> items(String tenantId, String setCode) {
        return jdbc.sql("""
                        SELECT set_code, code, label, ref_set_code, sort_order, enabled, remark, content, rule_type, severity FROM meta_item
                        WHERE tenant_id = :tenantId AND set_code = :setCode ORDER BY sort_order, code
                        """)
                .param("tenantId", tenantId).param("setCode", setCode)
                .query(this::mapItem).list();
    }

    public List<MetaItemRow> allItems(String tenantId) {
        return jdbc.sql("""
                        SELECT set_code, code, label, ref_set_code, sort_order, enabled, remark, content, rule_type, severity FROM meta_item
                        WHERE tenant_id = :tenantId ORDER BY set_code, sort_order, code
                        """)
                .param("tenantId", tenantId)
                .query(this::mapItem).list();
    }

    public Optional<MetaItemRow> item(String tenantId, String setCode, String code) {
        return jdbc.sql("""
                        SELECT set_code, code, label, ref_set_code, sort_order, enabled, remark, content, rule_type, severity FROM meta_item
                        WHERE tenant_id = :tenantId AND set_code = :setCode AND code = :code
                        """)
                .param("tenantId", tenantId).param("setCode", setCode).param("code", code)
                .query(this::mapItem).optional();
    }

    public long itemCount(String tenantId, String setCode) {
        return jdbc.sql("SELECT COUNT(*) FROM meta_item WHERE tenant_id = :tenantId AND set_code = :setCode")
                .param("tenantId", tenantId).param("setCode", setCode).query(Long.class).single();
    }

    public Map<String, Long> itemCounts(String tenantId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT set_code, COUNT(*) AS total FROM meta_item WHERE tenant_id = :tenantId
                        GROUP BY set_code
                        """)
                .param("tenantId", tenantId)
                .query(rs -> {
                    while (rs.next()) {
                        counts.put(rs.getString("set_code"), rs.getLong("total"));
                    }
                    return counts;
                });
        return counts;
    }

    private MetaItemRow mapItem(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new MetaItemRow(rs.getString("set_code"), rs.getString("code"), rs.getString("label"),
                rs.getString("ref_set_code"), rs.getInt("sort_order"), rs.getBoolean("enabled"),
                rs.getString("remark"), rs.getString("content"), rs.getString("rule_type"), rs.getString("severity"));
    }
}
