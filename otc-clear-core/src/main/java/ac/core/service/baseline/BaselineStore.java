package ac.core.service.baseline;

import ac.core.service.DataLayout;
import ac.core.service.importflow.StoredUpload;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Immutable per-batch copy of the imported workbook.
 *
 * <p>Uploads are purged once their TTL expires, so the baseline keeps the workbook available for
 * formula recalculation at export time. The copy is written once and never modified; every read
 * verifies the recorded hash so a tampered file is reported instead of silently used.
 */
@Slf4j
@Component
public class BaselineStore {

    private static final String BASE_NAME = "source";

    private final DataLayout dataLayout;

    public BaselineStore(DataLayout dataLayout) {
        this.dataLayout = dataLayout;
    }

    /** True for the source types that carry formulas. */
    public static boolean isWorkbook(String sourceType) {
        if (sourceType == null) {
            return false;
        }
        String upper = sourceType.toUpperCase(Locale.ROOT);
        return "XLSX".equals(upper) || "XLS".equals(upper);
    }

    /**
     * Copies the uploaded workbook into the batch folder.
     *
     * @return the stored file, or empty for CSV uploads and when the copy failed
     */
    public Optional<Path> save(String tenantId, String batchId, StoredUpload upload) {
        if (!isWorkbook(upload.sourceType())) {
            return Optional.empty();
        }
        Path folder = dataLayout.batchFolder(tenantId, batchId);
        Path target = folder.resolve(BASE_NAME + "." + upload.sourceType().toLowerCase(Locale.ROOT));
        if (Files.isRegularFile(target)) {
            return Optional.of(target);
        }
        Path temp = folder.resolve(BASE_NAME + ".tmp");
        try {
            Files.copy(upload.path(), temp, StandardCopyOption.REPLACE_EXISTING);
            String copied = sha256(temp);
            if (!copied.equalsIgnoreCase(String.valueOf(upload.sha256()))) {
                Files.deleteIfExists(temp);
                log.error("Baseline copy of upload {} does not match its hash", upload.uploadId());
                return Optional.empty();
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(target);
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot store the baseline for batch {}: {}", batchId, e.getMessage());
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                log.debug("Cannot remove the temp baseline {}", temp);
            }
            return Optional.empty();
        }
    }

    /**
     * Resolves the baseline of a batch and verifies it still matches the imported hash.
     *
     * @param expectedSha256 hash recorded when the batch was imported
     */
    public Optional<Path> find(String tenantId, String batchId, String expectedSha256) {
        Path folder = dataLayout.existingBatchFolder(tenantId, batchId);
        if (folder == null) {
            return Optional.empty();
        }
        for (String extension : new String[] {"xlsx", "xls"}) {
            Path candidate = folder.resolve(BASE_NAME + "." + extension);
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                if (expectedSha256 != null && !expectedSha256.isBlank()
                        && !expectedSha256.equalsIgnoreCase(sha256(candidate))) {
                    log.error("Baseline of batch {} no longer matches the imported hash", batchId);
                    return Optional.empty();
                }
                return Optional.of(candidate);
            } catch (IOException e) {
                log.warn("Cannot verify the baseline of batch {}: {}", batchId, e.getMessage());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Streams the file through SHA-256 without loading it into memory. */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
