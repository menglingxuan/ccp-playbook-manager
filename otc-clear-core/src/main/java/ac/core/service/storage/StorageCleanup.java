package ac.core.service.storage;

import ac.core.common.TimeProvider;
import ac.core.service.DataLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes temporary files that a failed or interrupted operation left behind.
 *
 * <p>Every writer of the data directory creates files next to their target and renames them on
 * success ({@code *.tmp}). A crash or a full disk leaves them behind and nothing else cleans them, so
 * the disk would grow silently. Only files older than the grace period are touched, so a write that
 * is still in progress is never removed.
 */
@Slf4j
@Component
public class StorageCleanup {

    /** Temporary files are only removed once they are this old. */
    private static final long TEMP_GRACE_HOURS = 1;

    private final DataLayout dataLayout;
    private final TimeProvider time;

    public StorageCleanup(DataLayout dataLayout, TimeProvider time) {
        this.dataLayout = dataLayout;
        this.time = time;
    }

    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000L, initialDelay = 15 * 60 * 1000L)
    public void cleanTemporaryFiles() {
        Path tenants = dataLayout.root().resolve("tenants");
        if (!Files.isDirectory(tenants)) {
            return;
        }
        LocalDateTime threshold = time.nowUtc().minusHours(TEMP_GRACE_HOURS);
        try (Stream<Path> directories = Files.list(tenants)) {
            directories.filter(Files::isDirectory)
                    .forEach(tenant -> cleanTenant(tenant, threshold));
        } catch (IOException e) {
            log.warn("Temporary file cleanup failed: {}", e.getMessage());
        }
    }

    private void cleanTenant(Path tenantDirectory, LocalDateTime threshold) {
        int removed = 0;
        try (Stream<Path> paths = Files.walk(tenantDirectory)) {
            // streaming instead of collecting keeps the memory flat on a large data directory
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (!isTemporary(path)) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(path).toInstant()
                            .isBefore(threshold.toInstant(ZoneOffset.UTC))) {
                        Files.deleteIfExists(path);
                        removed++;
                    }
                } catch (IOException e) {
                    log.debug("Cannot remove the temporary file {}", path, e);
                }
            }
        } catch (IOException e) {
            log.warn("Temporary file cleanup failed for {}: {}", tenantDirectory.getFileName(),
                    e.getMessage());
        }
        if (removed > 0) {
            log.info("Removed {} stale temporary file(s) of tenant {}", removed,
                    tenantDirectory.getFileName());
        }
    }

    private static boolean isTemporary(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        return name.endsWith(".tmp") && Files.isRegularFile(path);
    }
}
