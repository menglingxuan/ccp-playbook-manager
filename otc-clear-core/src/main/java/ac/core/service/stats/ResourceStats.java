package ac.core.service.stats;

import java.util.List;

/**
 * Resource usage snapshot of one tenant.
 *
 * @param generatedAt   display timestamp of the snapshot
 * @param tenantId      bound tenant
 * @param dataRoot      configured data root
 * @param tenantPath    absolute tenant directory
 * @param metrics       which optional figure groups are enabled
 * @param totalFiles    regular files below the tenant directory
 * @param totalBytes    bytes of those files
 * @param diskTotalBytes total size of the file store holding the data root, 0 when disabled
 * @param diskFreeBytes  usable space of that file store, 0 when disabled
 * @param areas         per directory breakdown
 * @param database      database identity, file size and row counts
 * @param runtime       JVM and host figures
 */
public record ResourceStats(
        String generatedAt,
        String tenantId,
        String dataRoot,
        String tenantPath,
        Metrics metrics,
        long totalFiles,
        long totalBytes,
        long diskTotalBytes,
        long diskFreeBytes,
        List<StorageArea> areas,
        Database database,
        Runtime runtime) {

    /**
     * Optional figure groups controlled by {@code app.resources.*}.
     *
     * @param heap whether heap and non-heap memory figures are reported
     * @param disk whether file store space is reported
     */
    public record Metrics(boolean heap, boolean disk) {
    }

    /**
     * One storage directory.
     *
     * @param key        stable key ({@code uploads}, {@code batches}, …)
     * @param name       display name
     * @param path       absolute path
     * @param files      regular files
     * @param bytes      bytes on disk
     * @param newestText display timestamp of the newest file, empty when the area is empty
     * @param note       what the area holds and whether it is cleaned up automatically
     */
    public record StorageArea(String key, String name, String path, long files, long bytes,
            String newestText, String note) {
    }

    /**
     * Database figures.
     *
     * @param product   database product name
     * @param version   database product version
     * @param display   {@code product version}
     * @param url       JDBC URL without credentials
     * @param fileBytes bytes of the database files of this tenant
     * @param files     number of database files
     * @param tables    row counts of the business tables
     * @param totalRows sum of {@code tables}
     */
    public record Database(String product, String version, String display, String url, long fileBytes,
            long files, List<TableStat> tables, long totalRows) {
    }

    /**
     * Row count of one table.
     *
     * @param table technical table name
     * @param name  display name
     * @param rows  rows of the current tenant
     * @param note  extra hint, empty when there is nothing to explain
     */
    public record TableStat(String table, String name, long rows, String note) {
    }

    /**
     * JVM and host figures.
     *
     * @param heapUsedBytes    used heap, 0 when heap metrics are disabled
     * @param heapMaxBytes     maximum heap, 0 when heap metrics are disabled
     * @param nonHeapUsedBytes used non-heap memory, 0 when heap metrics are disabled
     * @param threads          live thread count
     * @param uptimeSeconds    JVM uptime
     * @param processors       available processors
     * @param systemLoad       system load average, negative when unavailable
     */
    public record Runtime(long heapUsedBytes, long heapMaxBytes, long nonHeapUsedBytes, int threads,
            long uptimeSeconds, int processors, double systemLoad) {
    }
}
