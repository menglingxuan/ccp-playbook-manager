package ac.core.service.importflow;

import java.util.List;

/**
 * Import submission.
 *
 * @param uploadId upload identifier
 * @param sheets   sheets to import
 * @param force    continue when the same file and sheet were imported before
 */
public record ImportCommitRequest(String uploadId, List<ImportSelection> sheets, boolean force) {

    public ImportCommitRequest {
        sheets = sheets == null ? List.of() : List.copyOf(sheets);
    }
}
