package ac.core.service.importflow;

import ac.core.common.ApiException;
import ac.core.common.Ids;
import ac.core.common.TimeProvider;
import ac.core.config.AppProperties;
import ac.core.service.DataLayout;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Stores uploaded files on disk with a sha256 fingerprint and a small sidecar metadata file, so
 * uploads survive a restart until their TTL expires.
 */
@Slf4j
@Component
public class UploadStore {

    private static final String META_SUFFIX = ".meta";

    private final DataLayout dataLayout;
    private final AppProperties properties;
    private final TimeProvider time;

    public UploadStore(DataLayout dataLayout, AppProperties properties, TimeProvider time) {
        this.dataLayout = dataLayout;
        this.properties = properties;
        this.time = time;
    }

    public StoredUpload save(String tenantId, String originalFileName, InputStream content, long declaredSize) {
        String fileName = sanitizeFileName(originalFileName);
        String sourceType = detectType(fileName);
        long limit = properties.getLimits().getMaxFileSizeBytes();
        if (declaredSize > limit) {
            throw ApiException.limit("File exceeds the " + (limit / (1024 * 1024)) + " MB limit");
        }

        String uploadId = Ids.newId();
        LocalDate today = LocalDate.now(TimeProvider.DISPLAY_ZONE);
        Path directory = dataLayout.uploadsDir(tenantId, today);
        Path target = dataLayout.resolveInside(directory, uploadId + "." + sourceType.toLowerCase(Locale.ROOT));

        MessageDigest digest = sha256();
        long written = 0;
        try (DigestInputStream input = new DigestInputStream(content, digest);
                OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                written += read;
                if (written > limit) {
                    output.close();
                    Files.deleteIfExists(target);
                    throw ApiException.limit("File exceeds the " + (limit / (1024 * 1024)) + " MB limit");
                }
                output.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new ApiException(ac.core.common.ErrorCode.INTERNAL_ERROR, "Cannot store upload: " + e.getMessage());
        }

        StoredUpload upload = new StoredUpload(uploadId, fileName, sourceType,
                HexFormat.of().formatHex(digest.digest()), written, target, time.nowUtc());
        writeMeta(directory, upload);
        return upload;
    }

    public StoredUpload require(String tenantId, String uploadId) {
        if (uploadId == null || !uploadId.matches("[0-9a-fA-F-]{36}")) {
            throw ApiException.invalid("Invalid upload id");
        }
        Path directory = findDirectory(tenantId, uploadId)
                .orElseThrow(() -> ApiException.notFound("Upload " + uploadId + " does not exist or has expired"));
        StoredUpload upload = readMeta(directory, uploadId)
                .orElseThrow(() -> ApiException.notFound("Upload metadata is missing for " + uploadId));
        if (!Files.isRegularFile(upload.path())) {
            throw ApiException.notFound("Uploaded file is no longer available");
        }
        return upload;
    }

    private Optional<Path> findDirectory(String tenantId, String uploadId) {
        Path root = dataLayout.uploadsRoot(tenantId);
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve(uploadId + META_SUFFIX)))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void writeMeta(Path directory, StoredUpload upload) {
        Properties properties = new Properties();
        properties.setProperty("uploadId", upload.uploadId());
        properties.setProperty("fileName", upload.fileName());
        properties.setProperty("sourceType", upload.sourceType());
        properties.setProperty("sha256", upload.sha256());
        properties.setProperty("sizeBytes", Long.toString(upload.sizeBytes()));
        properties.setProperty("file", upload.path().getFileName().toString());
        properties.setProperty("createdAt", upload.createdAt().toString());
        try (OutputStream out = Files.newOutputStream(directory.resolve(upload.uploadId() + META_SUFFIX))) {
            properties.store(out, "upload metadata");
        } catch (IOException e) {
            log.warn("Cannot write upload metadata for {}: {}", upload.uploadId(), e.getMessage());
        }
    }

    private Optional<StoredUpload> readMeta(Path directory, String uploadId) {
        Properties props = new Properties();
        Path metaFile = directory.resolve(uploadId + META_SUFFIX);
        try (InputStream in = Files.newInputStream(metaFile)) {
            props.load(in);
            return Optional.of(new StoredUpload(
                    props.getProperty("uploadId", uploadId),
                    props.getProperty("fileName", ""),
                    props.getProperty("sourceType", "CSV"),
                    props.getProperty("sha256", ""),
                    Long.parseLong(props.getProperty("sizeBytes", "0")),
                    directory.resolve(props.getProperty("file", "")),
                    LocalDateTime.parse(props.getProperty("createdAt", time.nowUtc().toString()))));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Removes uploads older than the configured TTL. */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 600_000L)
    public void cleanExpired() {
        long ttlMinutes = properties.getLimits().getUploadTtlMinutes();
        if (ttlMinutes <= 0) {
            return;
        }
        LocalDateTime threshold = time.nowUtc().minusMinutes(ttlMinutes);
        Path root = dataLayout.root().resolve("tenants");
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var tenants = Files.list(root)) {
            tenants.filter(Files::isDirectory).forEach(tenant -> cleanTenant(tenant, threshold));
        } catch (IOException e) {
            log.warn("Upload cleanup failed: {}", e.getMessage());
        }
    }

    private void cleanTenant(Path tenantDirectory, LocalDateTime threshold) {
        Path uploads = tenantDirectory.resolve("uploads");
        if (!Files.isDirectory(uploads)) {
            return;
        }
        try (var days = Files.list(uploads)) {
            days.filter(Files::isDirectory).forEach(day -> {
                try (var files = Files.list(day)) {
                    files.forEach(file -> deleteIfExpired(file, threshold));
                } catch (IOException e) {
                    log.debug("Cannot list {}", day, e);
                }
            });
        } catch (IOException e) {
            log.debug("Cannot list {}", uploads, e);
        }
    }

    private void deleteIfExpired(Path file, LocalDateTime threshold) {
        try {
            LocalDateTime modified = LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(),
                    java.time.ZoneOffset.UTC);
            if (modified.isBefore(threshold)) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            log.debug("Cannot delete {}", file, e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.invalid("File name is required");
        }
        String base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[\\p{Cntrl}]", "").trim();
        if (base.contains("..")) {
            throw ApiException.invalid("Invalid file name");
        }
        return base;
    }

    static String detectType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx")) {
            return "XLSX";
        }
        if (lower.endsWith(".xls")) {
            return "XLS";
        }
        if (lower.endsWith(".csv")) {
            return "CSV";
        }
        throw new ApiException(ac.core.common.ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "Only .xlsx, .xls and .csv files are supported");
    }
}
