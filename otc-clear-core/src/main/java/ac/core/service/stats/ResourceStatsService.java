package ac.core.service.stats;

import ac.core.common.TimeProvider;
import ac.core.config.AppProperties;
import ac.core.service.DataLayout;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Collects the resource figures of one tenant: file areas, database rows and JVM state.
 *
 * <p>Everything is read on demand; the walk is bounded by the tenant directory, which holds the
 * upload cache, the immutable batch baselines, the exports and the database files.
 */
@Slf4j
@Service
public class ResourceStatsService {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Business tables counted for the tenant; every one of them carries {@code tenant_id}. */
    private static final List<String[]> TABLES = List.of(
            new String[] {"import_task", "导入任务", ""},
            new String[] {"import_batch", "批次", ""},
            new String[] {"test_case", "用例", "含软删除记录"},
            new String[] {"export_job", "导出记录", ""},
            new String[] {"audit_log", "审计日志", ""},
            new String[] {"base_product", "基产品", ""},
            new String[] {"base_product_field", "基产品字段", ""},
            new String[] {"product", "产品", ""},
            new String[] {"product_field", "产品字段", ""},
            new String[] {"meta_set", "元数据集合", ""},
            new String[] {"meta_item", "元数据项", ""},
            new String[] {"app_setting", "应用设置", ""});

    private static final List<String[]> AREAS = List.of(
            new String[] {"uploads", "上传缓存",
                    "导入时的源文件副本；超过保留期（app.limits.upload-ttl-minutes）由定时任务清理"},
            new String[] {"batches", "批次基线",
                    "每个批次不可变的 source.xlsx，用于导出时重算公式；批次删除后才会清理"},
            new String[] {"exports", "导出文件", "导出管理中已存档的 CSV，删除记录时一并删除"},
            new String[] {"jobs", "外部任务", "外部派发的中间产物"},
            new String[] {"db", "数据库文件", "H2 数据文件与跟踪日志，随批次数与用例数增长"});

    private final DataLayout dataLayout;
    private final DatabaseInfoProvider databaseInfo;
    private final AppProperties properties;
    private final JdbcClient jdbc;
    private final TimeProvider time;

    public ResourceStatsService(DataLayout dataLayout, DatabaseInfoProvider databaseInfo,
            AppProperties properties, JdbcClient jdbc, TimeProvider time) {
        this.dataLayout = dataLayout;
        this.databaseInfo = databaseInfo;
        this.properties = properties;
        this.jdbc = jdbc;
        this.time = time;
    }

    public ResourceStats stats(String tenantId) {
        Path tenantRoot = dataLayout.tenantRoot(tenantId).toAbsolutePath().normalize();
        List<ResourceStats.StorageArea> areas = new ArrayList<>();
        long totalFiles = 0;
        long totalBytes = 0;
        for (String[] area : AREAS) {
            ResourceStats.StorageArea measured = measure(tenantRoot.resolve(area[0]), area[0], area[1], area[2]);
            areas.add(measured);
            totalFiles += measured.files();
            totalBytes += measured.bytes();
        }
        boolean heapMetrics = properties.getResources().isHeapMetricsEnabled();
        boolean diskMetrics = properties.getResources().isDiskMetricsEnabled();
        long[] disk = diskMetrics ? diskUsage(tenantRoot) : new long[] {0, 0};

        DatabaseInfoProvider.DatabaseInfo info = databaseInfo.info();
        List<ResourceStats.TableStat> tables = tables(tenantId);
        long databaseBytes = areas.stream().filter(area -> "db".equals(area.key()))
                .mapToLong(ResourceStats.StorageArea::bytes).sum();
        long databaseFiles = areas.stream().filter(area -> "db".equals(area.key()))
                .mapToLong(ResourceStats.StorageArea::files).sum();
        ResourceStats.Database database = new ResourceStats.Database(info.product(), info.version(),
                info.display(), scrubUrl(info.url()), databaseBytes, databaseFiles, tables,
                tables.stream().mapToLong(ResourceStats.TableStat::rows).sum());

        return new ResourceStats(TimeProvider.display(time.nowUtc()), tenantId,
                properties.getDataRoot(), display(tenantRoot), new ResourceStats.Metrics(heapMetrics, diskMetrics),
                totalFiles, totalBytes, disk[0], disk[1], areas, database, runtime(heapMetrics));
    }

    private ResourceStats.StorageArea measure(Path directory, String key, String name, String note) {
        if (!Files.isDirectory(directory)) {
            return new ResourceStats.StorageArea(key, name, display(directory), 0, 0, "", note);
        }
        long files = 0;
        long bytes = 0;
        LocalDateTime newest = null;
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.toList()) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                files++;
                bytes += Files.size(path);
                LocalDateTime modified = LocalDateTime.ofInstant(Files.getLastModifiedTime(path).toInstant(),
                        ZoneOffset.UTC);
                if (newest == null || modified.isAfter(newest)) {
                    newest = modified;
                }
            }
        } catch (IOException e) {
            log.warn("Cannot measure {}: {}", directory, e.getMessage());
        }
        return new ResourceStats.StorageArea(key, name, display(directory), files, bytes,
                newest == null ? "" : TimeProvider.display(newest), note);
    }

    private List<ResourceStats.TableStat> tables(String tenantId) {
        List<ResourceStats.TableStat> stats = new ArrayList<>();
        for (String[] table : TABLES) {
            long rows;
            try {
                rows = jdbc.sql("SELECT COUNT(*) FROM " + table[0] + " WHERE tenant_id = :tenantId")
                        .param("tenantId", tenantId).query(Long.class).single();
            } catch (RuntimeException e) {
                log.debug("Cannot count {}: {}", table[0], e.getMessage());
                continue;
            }
            stats.add(new ResourceStats.TableStat(table[0], table[1], rows, table[2]));
        }
        return stats;
    }

    private static ResourceStats.Runtime runtime(boolean heapMetrics) {
        java.lang.Runtime vm = java.lang.Runtime.getRuntime();
        java.lang.management.MemoryMXBean memory = heapMetrics
                ? java.lang.management.ManagementFactory.getMemoryMXBean() : null;
        long heapUsed = memory == null ? 0 : memory.getHeapMemoryUsage().getUsed();
        long heapMax = memory == null ? 0 : memory.getHeapMemoryUsage().getMax() > 0
                ? memory.getHeapMemoryUsage().getMax() : vm.maxMemory();
        long nonHeap = memory == null ? 0 : memory.getNonHeapMemoryUsage().getUsed();
        int threads = java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount();
        long uptime = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000L;
        double load = java.lang.management.ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        return new ResourceStats.Runtime(heapUsed, heapMax, nonHeap, threads, uptime,
                vm.availableProcessors(), load);
    }

    private static long[] diskUsage(Path path) {
        Path probe = path;
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        if (probe == null) {
            return new long[] {0, 0};
        }
        try {
            FileStore store = Files.getFileStore(probe);
            return new long[] {store.getTotalSpace(), store.getUsableSpace()};
        } catch (IOException e) {
            return new long[] {0, 0};
        }
    }

    /** Keeps the JDBC URL readable but drops any inline credentials. */
    private static String scrubUrl(String url) {
        if (url == null) {
            return "";
        }
        String scrubbed = url;
        int semicolon = scrubbed.toLowerCase(Locale.ROOT).indexOf(";password=");
        if (semicolon >= 0) {
            scrubbed = scrubbed.substring(0, semicolon);
        }
        return scrubbed;
    }

    private static String display(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
