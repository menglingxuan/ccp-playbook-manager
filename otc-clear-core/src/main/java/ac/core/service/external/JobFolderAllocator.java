package ac.core.service.external;

import ac.core.service.export.ExportService;
import ac.core.common.JsonSupport;
import ac.core.common.TimeProvider;
import ac.core.service.DataLayout;
import ac.domain.export.CsvFormatOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Allocates the job folder and writes the input CSV plus a manifest. */
@Component
public class JobFolderAllocator {

    private final DataLayout dataLayout;
    private final JsonSupport json;

    public JobFolderAllocator(DataLayout dataLayout, JsonSupport json) {
        this.dataLayout = dataLayout;
        this.json = json;
    }

    /**
     * Paths created for a job.
     *
     * @param folder   job folder
     * @param inputCsv generated input file
     * @param manifest manifest file describing the dispatch
     */
    public record JobFiles(Path folder, Path inputCsv, Path manifest) {
    }

    public JobFiles allocate(String tenantId, String jobId, String productCode, String requestId,
            ExportService.CsvContent content, CsvFormatOptions options, LocalDate today) {
        Path folder = dataLayout.jobFolder(tenantId, jobId, today);
        Path inputDir = dataLayout.resolveInside(folder, "input");
        try {
            Files.createDirectories(inputDir);
            Path csv = dataLayout.resolveInside(inputDir, jobId + ".csv");
            Files.writeString(csv, renderCsv(content, options), StandardCharsets.UTF_8);

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("requestId", requestId);
            manifest.put("jobId", jobId);
            manifest.put("productType", productCode);
            manifest.put("rowCount", content.rows().size());
            manifest.put("headers", content.headers());
            manifest.put("delimiter", String.valueOf(options.delimiter()));
            manifest.put("quoteAll", options.quoteAll());
            manifest.put("createdAt", TimeProvider.display(
                    java.time.LocalDateTime.ofInstant(java.time.Instant.now(), java.time.ZoneOffset.UTC)));
            Path manifestFile = dataLayout.resolveInside(inputDir, "manifest.json");
            Files.writeString(manifestFile, json.toPrettyJson(manifest), StandardCharsets.UTF_8);
            return new JobFiles(folder, csv, manifestFile);
        } catch (IOException e) {
            throw new ac.core.common.ApiException(ac.core.common.ErrorCode.INTERNAL_ERROR,
                    "Cannot prepare the job folder: " + e.getMessage());
        }
    }

    private String renderCsv(ExportService.CsvContent content, CsvFormatOptions options) {
        return ac.domain.export.CsvWriter.toText(options, content.headers(), content.rows());
    }
}
