package ac.core.service.external;

import ac.core.common.Ids;
import ac.core.common.JsonSupport;
import ac.core.common.TimeProvider;
import ac.core.config.AppProperties;
import ac.core.persistence.ExternalJobRow;
import ac.core.persistence.ExternalOutputRepository;
import ac.core.persistence.ExternalOutputRow;
import ac.core.persistence.ImportBatchRepository;
import ac.core.persistence.ImportBatchRow;
import ac.core.persistence.ImportTaskRepository;
import ac.core.persistence.ImportTaskRow;
import ac.core.persistence.TestCaseRecord;
import ac.core.persistence.TestCaseRepository;
import ac.core.service.DataLayout;
import ac.core.service.cases.CaseJsonCodec;
import ac.core.service.product.ProductGuesser;
import ac.domain.analysis.PreviewOptions;
import ac.domain.analysis.SheetAnalysis;
import ac.domain.analysis.SheetAnalyzer;
import ac.domain.casefile.CaseRow;
import ac.domain.casefile.ValidationIssue;
import ac.domain.ids.Hashing;
import ac.domain.layout.SheetLayout;
import ac.domain.product.ProductSchema;
import ac.domain.table.CsvTableReader;
import ac.domain.table.RawTable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Registers and imports the CSV files produced by the external tool. */
@Slf4j
@Component
public class OutputScanner {

    private static final int INSERT_CHUNK = 500;

    private final ExternalOutputRepository outputRepository;
    private final ImportTaskRepository taskRepository;
    private final ImportBatchRepository batchRepository;
    private final TestCaseRepository caseRepository;
    private final DataLayout dataLayout;
    private final ProductGuesser guesser;
    private final CaseJsonCodec codec;
    private final JsonSupport json;
    private final TimeProvider time;
    private final AppProperties properties;

    public OutputScanner(ExternalOutputRepository outputRepository, ImportTaskRepository taskRepository,
            ImportBatchRepository batchRepository, TestCaseRepository caseRepository, DataLayout dataLayout,
            ProductGuesser guesser, CaseJsonCodec codec, JsonSupport json, TimeProvider time,
            AppProperties properties) {
        this.outputRepository = outputRepository;
        this.taskRepository = taskRepository;
        this.batchRepository = batchRepository;
        this.caseRepository = caseRepository;
        this.dataLayout = dataLayout;
        this.guesser = guesser;
        this.codec = codec;
        this.json = json;
        this.time = time;
        this.properties = properties;
    }

    public ScanResult scan(String tenantId, ExternalJobRow job) {
        Path folder = Path.of(job.folderPath()).toAbsolutePath().normalize();
        Path tenantRoot = dataLayout.tenantRoot(tenantId);
        if (!folder.startsWith(tenantRoot)) {
            return new ScanResult(0, 0, 0, 1, List.of("Job folder is outside the tenant data directory"));
        }
        if (!Files.isDirectory(folder)) {
            return new ScanResult(0, 0, 0, 0, List.of("Job folder does not exist"));
        }

        List<Path> files = listCandidates(folder);
        List<String> messages = new ArrayList<>();
        int imported = 0;
        int skipped = 0;
        int failed = 0;
        String taskId = null;

        for (Path file : files) {
            String relative = folder.relativize(file).toString().replace('\\', '/');
            String sha;
            long size;
            try {
                sha = Hashing.sha256Hex(Files.readAllBytes(file));
                size = Files.size(file);
            } catch (IOException e) {
                failed++;
                messages.add(relative + ": " + e.getMessage());
                continue;
            }
            var existing = outputRepository.findBySha(tenantId, sha);
            if (existing.isPresent()) {
                skipped++;
                continue;
            }
            String outputId = Ids.newId();
            LocalDateTime now = time.nowUtc();
            outputRepository.insert(new ExternalOutputRow(outputId, tenantId, job.id(), file.getFileName().toString(),
                    relative, size, sha, null, "DISCOVERED", null, now, null));

            try {
                if (taskId == null) {
                    taskId = createTask(tenantId, job);
                }
                String batchId = importFile(tenantId, taskId, job, file, sha);
                outputRepository.updateStatus(tenantId, outputId, "IMPORTED", null, batchId, time.nowUtc());
                imported++;
            } catch (RuntimeException e) {
                log.warn("Cannot import external output {}: {}", relative, e.getMessage());
                outputRepository.updateStatus(tenantId, outputId, "FAILED", e.getMessage(), null, null);
                failed++;
                messages.add(relative + ": " + e.getMessage());
            }
        }

        if (taskId != null) {
            taskRepository.finish(tenantId, taskId, failed > 0 ? "PARTIAL" : "SUCCEEDED", files.size(), imported,
                    imported, failed, null, "External output scan", time.nowUtc());
        }
        return new ScanResult(files.size(), imported, skipped, failed, messages);
    }

    private String createTask(String tenantId, ExternalJobRow job) {
        String taskId = Ids.newId();
        LocalDateTime now = time.nowUtc();
        taskRepository.insert(new ImportTaskRow(taskId, tenantId, "EXTERNAL_OUTPUT", "RUNNING",
                "external-output-" + Ids.shortId(job.id()), null, 0, 0, 0, 0, null, null, null, "system", now,
                now, null));
        return taskId;
    }

    private String importFile(String tenantId, String taskId, ExternalJobRow job, Path file, String sha) {
        RawTable table = CsvTableReader.read(file, properties.getLimits().getMaxRowsPerSheet());
        ProductSchema schema = guesser.guess(table);
        SheetAnalysis analysis = SheetAnalyzer.analyze(schema, table, PreviewOptions.AUTO,
                file.getFileName().toString(), properties.getIds().getHashLength(),
                properties.getIds().isNormalizeFormulaRefs());
        if (analysis.layout() == null) {
            String reason = analysis.issues().stream().map(ValidationIssue::message)
                    .reduce((left, right) -> left + "; " + right).orElse("layout could not be resolved");
            throw new IllegalArgumentException(reason);
        }
        SheetLayout layout = analysis.layout();
        String batchId = Ids.newId();
        LocalDateTime now = time.nowUtc();
        List<CaseRow> rows = analysis.rows();
        batchRepository.insert(new ImportBatchRow(batchId, tenantId, taskId, "EXTERNAL_OUTPUT", null,
                schema.code(), file.getFileName().toString(), sha, "CSV", table.sheetName(), layout.headerRow(),
                layout.tradeStartColumn(), layout.tradeEndColumn(), null, null, schema.schemaHash(), "ACTIVE",
                rows.size(), analysis.invalidRows(), "External output of job " + job.id(), "system", now, now,
                null));

        List<TestCaseRecord> records = new ArrayList<>(rows.size());
        for (CaseRow row : rows) {
            records.add(new TestCaseRecord(Ids.newId(), tenantId, batchId, schema.code(), "EXTERNAL_OUTPUT",
                    row.rowNo(), row.sourceRow(), row.testCaseId(), row.testCaseIdSource().name(),
                    row.originalRecordId(), row.businessRecordId(), row.keyValue(), codec.dataJson(schema, row),
                    row.managed().isEmpty() ? null : codec.managedJson(row.managed()),
                    row.formulas().isEmpty() ? null
                            : codec.formulaJson(schema, row.formulas(), row.unresolvedFormulas()),
                    row.extras().isEmpty() ? null : codec.extrasJson(row.extras()), row.formulas().size(),
                    row.searchText(), "ACTIVE", 0, null, now, now));
        }
        for (int start = 0; start < records.size(); start += INSERT_CHUNK) {
            caseRepository.insertAll(records.subList(start, Math.min(records.size(), start + INSERT_CHUNK)));
        }
        return batchId;
    }

    private List<Path> listCandidates(Path folder) {
        int maxFiles = properties.getLimits().getMaxScanFiles();
        long maxSize = properties.getLimits().getMaxScanFileSize();
        Path inputDir = folder.resolve("input");
        try (Stream<Path> stream = Files.walk(folder)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(inputDir))
                    .filter(path -> !isHidden(path))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                    .filter(path -> {
                        try {
                            return Files.size(path) <= maxSize;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(maxFiles)
                    .toList();
        } catch (IOException e) {
            log.warn("Cannot scan job folder {}: {}", folder, e.getMessage());
            return List.of();
        }
    }

    private static boolean isHidden(Path path) {
        Path name = path.getFileName();
        if (name == null) {
            return true;
        }
        String text = name.toString();
        return text.startsWith(".") || text.startsWith("~");
    }

    /** Parsed dispatch payload for the job detail page. */
    public Map<String, Object> describePayload(ExternalJobRow job) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("payload", json.toMapOrEmpty(job.dispatchPayload()));
        detail.put("response", job.lastResponse());
        return detail;
    }
}
