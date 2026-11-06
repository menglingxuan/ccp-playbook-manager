package ac.core.service;

import ac.core.common.ApiException;
import ac.core.config.AppProperties;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/** Resolves tenant scoped storage directories and guards against path traversal. */
@Component
public class DataLayout {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final Path root;

    public DataLayout(AppProperties properties) {
        this.root = Path.of(properties.getDataRoot()).toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path tenantRoot(String tenantId) {
        return resolveSafe(root.resolve("tenants").resolve(tenantId));
    }

    public Path uploadsRoot(String tenantId) {
        return ensureExists(tenantRoot(tenantId).resolve("uploads"));
    }

    public Path uploadsDir(String tenantId, LocalDate date) {
        return ensureExists(uploadsRoot(tenantId).resolve(date.format(DATE)));
    }

    public Path exportsDir(String tenantId, LocalDate date) {
        return ensureExists(tenantRoot(tenantId).resolve("exports").resolve(date.format(DATE)));
    }

    public Path jobsRoot(String tenantId) {
        return ensureExists(tenantRoot(tenantId).resolve("jobs"));
    }

    /** Job folder layout: {@code jobs/{yyyy}/{MM}/{jobId}}. */
    public Path jobFolder(String tenantId, String jobId, LocalDate date) {
        String monthText = date.getMonthValue() < 10
                ? "0" + date.getMonthValue() : Integer.toString(date.getMonthValue());
        return ensureExists(jobsRoot(tenantId)
                .resolve(Integer.toString(date.getYear()))
                .resolve(monthText)
                .resolve(jobId));
    }

    /** Ensures that the given path stays inside the provided root directory. */
    public Path resolveInside(Path rootDirectory, String relativePath) {
        Path normalizedRoot = rootDirectory.toAbsolutePath().normalize();
        Path candidate = normalizedRoot.resolve(relativePath).normalize();
        if (!candidate.startsWith(normalizedRoot)) {
            throw ApiException.invalid("Path escapes the allowed directory: " + relativePath);
        }
        return candidate;
    }

    private Path resolveSafe(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw ApiException.invalid("Path escapes the data root");
        }
        return normalized;
    }

    private Path ensureExists(Path path) {
        try {
            return java.nio.file.Files.createDirectories(path);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot create directory " + path, e);
        }
    }
}
