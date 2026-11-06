package ac.core.persistence;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access for {@code app_setting}. */
@Repository
public class SettingRepository {

    private final JdbcClient jdbc;

    public SettingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> find(String tenantId, String key) {
        return jdbc.sql("SELECT setting_value FROM app_setting WHERE tenant_id = :tenantId AND setting_key = :key")
                .param("tenantId", tenantId).param("key", key).query(String.class).optional();
    }

    public Map<String, String> findAll(String tenantId) {
        Map<String, String> values = new LinkedHashMap<>();
        jdbc.sql("SELECT setting_key, setting_value FROM app_setting WHERE tenant_id = :tenantId ORDER BY setting_key")
                .param("tenantId", tenantId)
                .query(rs -> {
                    while (rs.next()) {
                        values.put(rs.getString("setting_key"), rs.getString("setting_value"));
                    }
                    return values;
                });
        return values;
    }

    public void put(String tenantId, String key, String value, LocalDateTime updatedAt) {
        int updated = jdbc.sql("""
                        UPDATE app_setting SET setting_value = :value, updated_at = :updatedAt
                        WHERE tenant_id = :tenantId AND setting_key = :key
                        """)
                .param("value", value).param("updatedAt", updatedAt)
                .param("tenantId", tenantId).param("key", key).update();
        if (updated == 0) {
            jdbc.sql("""
                            INSERT INTO app_setting (tenant_id, setting_key, setting_value, updated_at)
                            VALUES (:tenantId, :key, :value, :updatedAt)
                            """)
                    .param("tenantId", tenantId).param("key", key).param("value", value)
                    .param("updatedAt", updatedAt).update();
        }
    }
}
