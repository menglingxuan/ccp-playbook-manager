package ac.core.service.external;

import java.util.List;

/**
 * Result of scanning a job folder.
 *
 * @param discovered number of eligible files found
 * @param imported   files parsed into an output batch
 * @param skipped    files already registered
 * @param failed     files that could not be imported
 * @param messages   human readable details
 */
public record ScanResult(int discovered, int imported, int skipped, int failed, List<String> messages) {

    public ScanResult {
        messages = List.copyOf(messages);
    }
}
