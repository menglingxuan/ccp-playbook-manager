package ac.core.service.stats;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Reports the product name and version of the database behind the datasource. */
@Slf4j
@Component
public class DatabaseInfoProvider {

    private final DataSource dataSource;
    private volatile DatabaseInfo cached;

    public DatabaseInfoProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Database identity.
     *
     * @param product database product name, for example {@code H2}
     * @param version database product version
     * @param driver  JDBC driver name
     * @param url     connection URL without credentials
     */
    public record DatabaseInfo(String product, String version, String driver, String url) {

        /** Display text such as {@code H2 2.4.240}. */
        public String display() {
            return product == null || product.isBlank() ? version : product + " " + version;
        }
    }

    /** Resolved once per process; the connection metadata does not change at runtime. */
    public DatabaseInfo info() {
        DatabaseInfo current = cached;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (cached == null) {
                cached = read();
            }
            return cached;
        }
    }

    private DatabaseInfo read() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            return new DatabaseInfo(metaData.getDatabaseProductName(), metaData.getDatabaseProductVersion(),
                    safe(metaData.getDriverName()), safe(metaData.getURL()));
        } catch (SQLException e) {
            log.warn("Cannot read database metadata: {}", e.getMessage());
            return new DatabaseInfo("unknown", "unknown", "", "");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
