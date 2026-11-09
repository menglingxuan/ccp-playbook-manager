package ac.core.service.clone;

import ac.core.common.ApiException;
import ac.core.common.Ids;
import ac.core.common.JsonSupport;
import ac.core.common.TimeProvider;
import ac.core.config.AppProperties;
import ac.core.persistence.ImportBatchRepository;
import ac.core.persistence.ImportBatchRow;
import ac.core.persistence.TestCaseRecord;
import ac.core.persistence.TestCaseRepository;
import ac.core.service.audit.AuditService;
import ac.core.service.cases.CaseJsonCodec;
import ac.core.service.cases.CaseService;
import ac.core.service.product.ProductCatalog;
import ac.domain.casefile.TestCaseIdSource;
import ac.domain.clone.NamingTemplate;
import ac.domain.product.FieldCodec;
import ac.domain.product.FieldDef;
import ac.domain.product.ProductSchema;
import ac.domain.casefile.Formulas;
import ac.domain.casefile.RecordIdGenerator;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Copies selected cases into a new batch and rewrites the key fields. */
@Slf4j
@Service
public class CloneService {

    private static final int MAX_TOTAL_ROWS = 2000;
    private static final int INSERT_CHUNK = 500;

    private final CaseService caseService;
    private final TestCaseRepository caseRepository;
    private final ImportBatchRepository batchRepository;
    private final ProductCatalog products;
    private final CaseJsonCodec codec;
    private final JsonSupport json;
    private final TimeProvider time;
    private final AuditService audit;
    private final AppProperties properties;

    public CloneService(CaseService caseService, TestCaseRepository caseRepository,
            ImportBatchRepository batchRepository, ProductCatalog products, CaseJsonCodec codec,
            JsonSupport json, TimeProvider time, AuditService audit, AppProperties properties) {
        this.caseService = caseService;
        this.caseRepository = caseRepository;
        this.batchRepository = batchRepository;
        this.products = products;
        this.codec = codec;
        this.json = json;
        this.time = time;
        this.audit = audit;
        this.properties = properties;
    }

    public ClonePreview preview(String tenantId, CloneRequest request) {
        Plan plan = plan(tenantId, request);
        List<ClonePreview.PlannedRow> sample = plan.rows().stream().limit(5)
                .map(row -> new ClonePreview.PlannedRow(row.source().id(), row.source().testCaseId(),
                        row.testCaseId(), row.data().get(plan.schema().uniqueField().key()) == null
                                ? null
                                : FieldCodec.canonical(plan.schema().uniqueField(),
                                        row.data().get(plan.schema().uniqueField().key())),
                        row.originalRecordId()))
                .toList();
        return new ClonePreview(plan.schema().code(), plan.sourceCount(), request.copies(),
                plan.rows().size(), sample, plan.conflicts());
    }

    @Transactional
    public CloneResult execute(String tenantId, CloneRequest request) {
        Plan plan = plan(tenantId, request);
        if (!plan.conflicts().isEmpty()) {
            throw new ApiException(ac.core.common.ErrorCode.CONFLICT,
                    "Duplicate trade ids would be created",
                    Map.of("conflicts", plan.conflicts()));
        }
        LocalDateTime now = time.nowUtc();
        String batchId = Ids.newId();
        ImportBatchRow batch = new ImportBatchRow(batchId, tenantId, null, null, "CLONE", plan.parentBatchId(),
                plan.schema().code(), "clone-" + plan.schema().code(), null, "CLONE", null, 0, 0, 0, null, null,
                plan.schema().schemaHash(), "ACTIVE", plan.rows().size(), 0, request.note(), "system", now, now,
                null);
        batchRepository.insert(batch);

        List<TestCaseRecord> records = new ArrayList<>(plan.rows().size());
        int rowNo = 0;
        for (PlannedRow row : plan.rows()) {
            rowNo++;
            TestCaseRecord source = row.source();
            records.add(new TestCaseRecord(Ids.newId(), tenantId, batchId, plan.schema().code(), "CLONE",
                    rowNo, source.sourceRow(), row.testCaseId(), TestCaseIdSource.CLONE.name(),
                    row.originalRecordId(), row.businessRecordId(), row.keyValue(),
                    codec.dataJson(plan.schema(), row.data()),
                    source.managedJson(), source.formulaJson(), source.extrasJson(),
                    source.formulaCount(), buildSearchText(plan.schema(), row), "ACTIVE", 0, source.id(), now,
                    now));
        }
        for (int start = 0; start < records.size(); start += INSERT_CHUNK) {
            caseRepository.insertAll(records.subList(start, Math.min(records.size(), start + INSERT_CHUNK)));
        }

        audit.record(tenantId, AuditService.ENTITY_BATCH, batchId, "CLONE",
                Map.of("sources", plan.sourceCount(), "copies", request.copies(), "rows", records.size()));
        return new CloneResult(batchId, records.size());
    }

    private Plan plan(String tenantId, CloneRequest request) {
        if (request.caseIds().isEmpty()) {
            throw ApiException.invalid("Select at least one case to clone");
        }
        int maxCopies = properties.getLimits().getMaxCloneCount();
        if (request.copies() < 1 || request.copies() > maxCopies) {
            throw ApiException.invalid("Copies must be between 1 and " + maxCopies);
        }
        if ((long) request.caseIds().size() * request.copies() > MAX_TOTAL_ROWS) {
            throw ApiException.limit("A single clone run is limited to " + MAX_TOTAL_ROWS + " rows");
        }

        List<TestCaseRecord> sources = caseService.requireAll(tenantId, request.caseIds());
        Set<String> productCodes = new LinkedHashSet<>();
        sources.forEach(source -> productCodes.add(source.productCode()));
        if (productCodes.size() != 1) {
            throw ApiException.invalid("Selected cases must belong to the same product");
        }
        ProductSchema schema = products.require(tenantId, productCodes.iterator().next());
        FieldDef keyField = schema.uniqueField();
        if (keyField == null) {
            throw ApiException.invalid("Product " + schema.code() + " has no unique field to rewrite");
        }

        String template = resolveTemplate(request.template(), keyField);
        NamingTemplate.validate(template);
        String uuid = Ids.newId().replace("-", "");
        LocalDate today = LocalDate.now(TimeProvider.DISPLAY_ZONE);

        String parentBatchId = sources.stream().map(TestCaseRecord::batchId).distinct().count() == 1
                ? sources.get(0).batchId() : null;

        List<PlannedRow> rows = new ArrayList<>(sources.size() * request.copies());
        Set<String> plannedKeys = new HashSet<>();
        for (TestCaseRecord source : sources) {
            Map<String, Object> baseData = new LinkedHashMap<>(json.toMap(source.dataJson()));
            Object baseKey = baseData.get(keyField.key());
            String baseKeyText = baseKey == null ? "" : FieldCodec.canonical(keyField, baseKey);
            for (int sequence = 1; sequence <= request.copies(); sequence++) {
                NamingTemplate.Context context = new NamingTemplate.Context(baseKeyText, sequence, uuid, today,
                        batchIdOf(source));
                String newKey = NamingTemplate.render(template, context);
                Map<String, Object> data = new LinkedHashMap<>(baseData);
                data.put(keyField.key(), newKey);
                String testCaseId = NamingTemplate.render(template,
                        new NamingTemplate.Context(source.testCaseId(), sequence, uuid, today, "clone"));
                String originalRecordId = RecordIdGenerator.cloneOriginalId(source.originalRecordId(), sequence,
                        properties.getIds().getHashLength());
                Map<String, String> formulas = codec.tradeFormulas(source.formulaJson());
                Map<String, String> hashFormulas = properties.getIds().isNormalizeFormulaRefs()
                        ? Formulas.normalized(formulas) : formulas;
                String businessRecordId = RecordIdGenerator.businessId(schema, data, hashFormulas,
                        properties.getIds().getHashLength());
                rows.add(new PlannedRow(source, data, testCaseId, originalRecordId, businessRecordId, newKey));
                plannedKeys.add(newKey);
            }
        }

        List<String> conflicts = new ArrayList<>();
        List<String> existing = caseRepository.existingKeys(tenantId, schema.code(), plannedKeys);
        existing.stream().limit(20).forEach(key -> conflicts.add("Trade id '" + key + "' already exists"));
        if (plannedKeys.size() < rows.size()) {
            Set<String> seen = new HashSet<>();
            for (PlannedRow row : rows) {
                if (!seen.add(row.keyValue())) {
                    conflicts.add("Generated trade id '" + row.keyValue() + "' appears more than once, "
                            + "use a template with {seq} or {uuid}");
                    break;
                }
            }
        }
        return new Plan(schema, parentBatchId, sources.size(), rows, conflicts);
    }

    private String batchIdOf(TestCaseRecord source) {
        return Ids.shortId(source.batchId());
    }

    private static String resolveTemplate(String requested, FieldDef keyField) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        if (keyField.clonePattern() != null && !keyField.clonePattern().isBlank()) {
            return keyField.clonePattern();
        }
        return "{base}-{seq:02}";
    }

    private String buildSearchText(ProductSchema schema, PlannedRow row) {
        StringBuilder sb = new StringBuilder();
        append(sb, row.testCaseId());
        append(sb, row.keyValue());
        for (FieldDef field : schema.fields()) {
            Object value = row.data().get(field.key());
            if (value != null) {
                append(sb, codec.displayValue(schema, field.key(), value));
            }
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(value);
    }

    private record PlannedRow(TestCaseRecord source, Map<String, Object> data, String testCaseId,
            String originalRecordId, String businessRecordId, String keyValue) {
    }

    private record Plan(ProductSchema schema, String parentBatchId, int sourceCount, List<PlannedRow> rows,
            List<String> conflicts) {
    }
}
