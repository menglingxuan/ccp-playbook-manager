package ac.core.service.importflow;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * An uploaded source file.
 *
 * @param uploadId     identifier used by the wizard
 * @param fileName     original file name, case preserved
 * @param sourceType   {@code XLSX}, {@code XLS} or {@code CSV}
 * @param sha256       SHA-256 of the content
 * @param md5          MD5 of the content, empty when it was not recorded
 * @param sizeBytes    file size
 * @param path         stored location
 * @param createdAt    upload time (UTC)
 */
public record StoredUpload(
        String uploadId,
        String fileName,
        String sourceType,
        String sha256,
        String md5,
        long sizeBytes,
        Path path,
        LocalDateTime createdAt) {
}
