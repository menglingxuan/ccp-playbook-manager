package ac.core.service.cases;

import ac.core.common.ApiException;
import ac.core.common.ErrorCode;
import ac.core.common.JsonSupport;
import ac.core.common.PageResult;
import ac.core.common.TimeProvider;
import ac.core.config.AppProperties;
import ac.core.persistence.CaseFilter;
import ac.core.persistence.TestCaseRecord;
import ac.core.persistence.TestCaseRepository;
import ac.core.service.audit.AuditService;
import ac.core.service.product.ProductCatalog;
import ac.domain.casefile.CaseValidator;
import ac.domain.casefile.RecordIdGenerator;
import ac.domain.casefile.ValidationIssue;
import ac.domain.product.FieldCodec;
import ac.domain.product.FieldDef;
import ac.domain.product.ProductSchema;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Case queries, vertical view, editing and deletion. */
@Service
public class CaseService {

    private static final int PREVIEW_FIELDS = 4;

    private final TestCaseRepository repository;
    private final ProductCatalog products;
    private final CaseJsonCodec codec;
    private final JsonSupport json;
    private final TimeProvider time;
    private final AuditService audit;
    private final AppProperties properties;

    public CaseService(TestCaseRepository repository, ProductCatalog products, CaseJsonCodec codec,
            JsonSupport json, TimeProvider time, AuditService audit, AppProperties properties) {
        this.repository = repository;
        this.products = products;
        this.codec = codec;
        this.json = json;
        this.time = time;
        this.audit = audit;
        this.properties = properties;
    }

    public PageResult<CaseSummary> search(String tenantId, CaseFilter filter) {
        List<TestCaseRecord> records = repository.page(tenantId, filter);
        long total = repository.count(tenantId, filter);
        List<CaseSummary> items = new ArrayList<>(records.size());
        Map<String, ProductSchema> schemas = new LinkedHashMap<>();
        for (TestCaseRecord record : records) {
            ProductSchema schema = schemas.computeIfAbsent(record.productCode(),
                    code -> products.require(tenantId, code));
            items.add(toSummary(record, schema));
        }
        return new PageResult<>(items, filter.page().page(), filter.page().size(), total);
    }

    public TestCaseRecord require(String tenantId, String caseId) {
        return repository.find(tenantId, caseId)
                .filter(row -> "ACTIVE".equals(row.status()))
                .orElseThrow(() -> ApiException.notFound("Case " + caseId + " does not exist"));
    }

    public List<TestCaseRecord> requireAll(String tenantId, Collection<String> caseIds) {
        List<TestCaseRecord> records = repository.findByIds(tenantId, caseIds);
        if (records.size() != caseIds.size()) {
            throw ApiException.notFound("Some selected cases no longer exist");
        }
        return records;
    }

    public CaseDetail detail(String tenantId, String caseId) {
        return toDetail(require(tenantId, caseId));
    }

    public CaseDetail toDetail(TestCaseRecord record) {
        return new CaseDetail(record.id(), record.batchId(), record.productCode(), record.origin(),
                record.rowNo(), record.sourceRow(), record.testCaseId(), record.testCaseIdSource(),
                record.originalRecordId(), record.businessRecordId(), record.keyValue(), record.version(),
                json.toMap(record.dataJson()), json.toMapOrEmpty(record.managedJson())
                        .entrySet().stream().collect(LinkedHashMap::new,
                                (map, entry) -> map.put(entry.getKey(), String.valueOf(entry.getValue())),
                                Map::putAll),
                codec.tradeFormulas(record.formulaJson()), codec.managedFormulas(record.formulaJson()),
                codec.unresolvedFormulas(record.formulaJson()),
                toStringMap(json.toMapOrEmpty(record.extrasJson())),
                record.createdAt(), record.updatedAt());
    }

    public VerticalView vertical(String tenantId, String caseId) {
        TestCaseRecord record = require(tenantId, caseId);
        ProductSchema schema = products.require(tenantId, record.productCode());
        Map<String, Object> data = json.toMap(record.dataJson());
        Map<String, String> managed = toStringMap(json.toMapOrEmpty(record.managedJson()));
        Map<String, String> extras = toStringMap(json.toMapOrEmpty(record.extrasJson()));
        Map<String, String> formulas = codec.tradeFormulas(record.formulaJson());
        Map<String, String> managedFormulas = codec.managedFormulas(record.formulaJson());

        List<VerticalView.VerticalRow> identity = new ArrayList<>();
        identity.add(new VerticalView.VerticalRow("testCaseId", "Test Case Id", nullToEmpty(record.testCaseId()),
                null, "TEXT", false));
        identity.add(new VerticalView.VerticalRow("originalRecordId", "Original Record Id",
                nullToEmpty(record.originalRecordId()), null, "TEXT", false));
        identity.add(new VerticalView.VerticalRow("businessRecordId", "Business Record Id",
                nullToEmpty(record.businessRecordId()), null, "TEXT", false));
        identity.add(new VerticalView.VerticalRow("sourceRow", "Source Row",
                record.sourceRow() == null ? "" : record.sourceRow().toString(), null, "INTEGER", false));

        List<VerticalView.VerticalRow> trade = new ArrayList<>();
        for (FieldDef field : schema.tradeFields()) {
            trade.add(new VerticalView.VerticalRow(field.key(), field.displayHeader(),
                    codec.displayValue(schema, field.key(), data.get(field.key())),
                    formulas.get(field.key()), field.type().name(), field.isKey()));
        }

        List<VerticalView.VerticalRow> managedRows = new ArrayList<>();
        managed.forEach((key, value) -> managedRows.add(new VerticalView.VerticalRow(key, key, value,
                managedFormulas.get(key), "TEXT", false)));

        List<VerticalView.VerticalRow> extraRows = new ArrayList<>();
        extras.forEach((key, value) -> extraRows.add(new VerticalView.VerticalRow(key, key, value, null,
                "TEXT", false)));

        List<VerticalView.VerticalSection> sections = new ArrayList<>();
        sections.add(new VerticalView.VerticalSection("identity", "标识", identity));
        sections.add(new VerticalView.VerticalSection("trade", "交易字段", trade));
        if (!managedRows.isEmpty()) {
            sections.add(new VerticalView.VerticalSection("managed", "管理字段", managedRows));
        }
        if (!extraRows.isEmpty()) {
            sections.add(new VerticalView.VerticalSection("extras", "未映射列", extraRows));
        }
        int rowCount = repository.countByBatch(tenantId, record.batchId());
        return new VerticalView(record.id(), record.testCaseId(), record.productCode(), record.rowNo(),
                rowCount, sections);
    }

    @Transactional
    public CaseDetail update(String tenantId, String caseId, UpdateCaseRequest request) {
        TestCaseRecord record = require(tenantId, caseId);
        ProductSchema schema = products.require(tenantId, record.productCode());
        CaseValidator.CaseValidation validation = CaseValidator.validate(schema, request.values());
        if (!validation.valid()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Some values are invalid",
                    Map.of("issues", validation.issues()));
        }

        Map<String, Object> merged = new LinkedHashMap<>(json.toMap(record.dataJson()));
        for (String key : request.values().keySet()) {
            merged.remove(key);
        }
        merged.putAll(validation.values());

        Map<String, String> formulas = codec.tradeFormulas(record.formulaJson());
        Map<String, String> managed = toStringMap(json.toMapOrEmpty(record.managedJson()));
        managed.putAll(request.managed());

        String businessRecordId = RecordIdGenerator.businessId(schema, merged, formulas,
                properties.getIds().getHashLength());
        String keyValue = null;
        if (schema.uniqueField() != null) {
            Object key = merged.get(schema.uniqueField().key());
            keyValue = key == null ? null : FieldCodec.canonical(schema.uniqueField(), key);
        }

        String searchText = buildSearchText(schema, record.testCaseId(), keyValue, merged, managed,
                toStringMap(json.toMapOrEmpty(record.extrasJson())));
        LocalDateTime now = time.nowUtc();
        int updated = repository.updateCase(tenantId, caseId, request.version(),
                codec.dataJson(schema, merged),
                codec.managedJson(managed),
                codec.formulaJson(schema, formulas, record.formulaJson()),
                formulas.size(), searchText, keyValue,
                businessRecordId.isEmpty() ? record.businessRecordId() : businessRecordId, now);
        if (updated == 0) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "The case was modified by someone else, reload and try again");
        }
        audit.record(tenantId, AuditService.ENTITY_CASE, caseId, "UPDATE", Map.of("fields", request.values().keySet()));
        return detail(tenantId, caseId);
    }

    @Transactional
    public void delete(String tenantId, String caseId) {
        require(tenantId, caseId);
        repository.softDelete(tenantId, caseId, time.nowUtc());
        audit.record(tenantId, AuditService.ENTITY_CASE, caseId, "DELETE", Map.of());
    }

    @Transactional
    public int bulkDelete(String tenantId, List<String> caseIds) {
        int limit = properties.getLimits().getMaxBulkDelete();
        if (caseIds == null || caseIds.isEmpty()) {
            throw ApiException.invalid("Select at least one case");
        }
        if (caseIds.size() > limit) {
            throw ApiException.limit("At most " + limit + " cases can be deleted at once");
        }
        int deleted = repository.softDeleteMany(tenantId, caseIds, time.nowUtc());
        audit.record(tenantId, AuditService.ENTITY_CASE, null, "DELETE",
                Map.of("count", deleted, "ids", caseIds.size()));
        return deleted;
    }

    private CaseSummary toSummary(TestCaseRecord record, ProductSchema schema) {
        Map<String, Object> data = json.toMap(record.dataJson());
        Map<String, String> formulas = codec.tradeFormulas(record.formulaJson());
        List<CaseSummary.PreviewField> preview = new ArrayList<>();
        for (FieldDef field : schema.tradeFields()) {
            if (preview.size() >= PREVIEW_FIELDS) {
                break;
            }
            preview.add(new CaseSummary.PreviewField(field.displayHeader(),
                    codec.displayValue(schema, field.key(), data.get(field.key())),
                    formulas.containsKey(field.key())));
        }
        return new CaseSummary(record.id(), record.batchId(), record.productCode(), record.origin(),
                record.rowNo(), record.testCaseId(), record.originalRecordId(), record.businessRecordId(),
                record.keyValue(), record.formulaCount(), record.version(), preview);
    }

    private String buildSearchText(ProductSchema schema, String testCaseId, String keyValue,
            Map<String, Object> data, Map<String, String> managed, Map<String, String> extras) {
        StringBuilder sb = new StringBuilder();
        append(sb, testCaseId);
        append(sb, keyValue);
        for (FieldDef field : schema.tradeFields()) {
            Object value = data.get(field.key());
            if (value != null) {
                append(sb, codec.displayValue(schema, field.key(), value));
            }
        }
        managed.values().forEach(value -> append(sb, value));
        extras.values().forEach(value -> append(sb, value));
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

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Map<String, String> toStringMap(Map<String, Object> source) {
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, value == null ? "" : String.valueOf(value)));
        return result;
    }

    /** Re-validates stored values, used by export prechecks. */
    public List<ValidationIssue> validate(String tenantId, TestCaseRecord record) {
        ProductSchema schema = products.require(tenantId, record.productCode());
        Map<String, Object> data = json.toMap(record.dataJson());
        Map<String, String> raw = new LinkedHashMap<>();
        data.forEach((key, value) -> raw.put(key, value == null ? "" : String.valueOf(value)));
        return CaseValidator.validate(schema, raw).issues();
    }
}
