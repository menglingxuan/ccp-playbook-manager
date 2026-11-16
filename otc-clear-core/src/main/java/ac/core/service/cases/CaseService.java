package ac.core.service.cases;

import ac.core.common.ApiException;
import ac.core.common.ErrorCode;
import ac.core.common.JsonSupport;
import ac.core.common.PageResult;
import ac.core.common.TimeProvider;
import ac.core.config.AppProperties;
import ac.core.persistence.CaseFilter;
import ac.core.persistence.MetaRepository;
import ac.core.persistence.TestCaseRecord;
import ac.core.persistence.TestCaseRepository;
import ac.core.service.meta.MetaSetType;
import ac.core.service.product.ProductCatalog;
import ac.domain.casefile.CaseValidator;
import ac.domain.casefile.Formulas;
import ac.domain.casefile.RecordIdGenerator;
import ac.domain.casefile.ValidationIssue;
import ac.domain.casefile.CellTexts;
import ac.domain.layout.ManagedColumnClassifier;
import ac.domain.product.FieldCodec;
import ac.domain.product.FieldDef;
import ac.domain.product.FieldRole;
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
    /** System columns that never take part in a bulk update. */
    private static final java.util.Set<String> BULK_READ_ONLY_FIELDS = java.util.Set.of("testCaseId",
            "originalRecordId", "businessRecordId", "sourceRow");
    private static final int BULK_SAMPLES = 5;
    private static final int BULK_ISSUES = 20;
    private static final int BULK_FORMULA_LENGTH = 512;

    private final TestCaseRepository repository;
    private final ProductCatalog products;
    private final CaseJsonCodec codec;
    private final JsonSupport json;
    private final TimeProvider time;
    private final AppProperties properties;
    private final ac.core.persistence.MetaRepository metaStore;

    public CaseService(TestCaseRepository repository, ProductCatalog products, CaseJsonCodec codec,
            JsonSupport json, TimeProvider time, AppProperties properties,
            ac.core.persistence.MetaRepository metaStore) {
        this.repository = repository;
        this.products = products;
        this.codec = codec;
        this.json = json;
        this.time = time;
        this.properties = properties;
        this.metaStore = metaStore;
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
        CellTexts texts = codec.texts(record);
        return new CaseDetail(record.id(), record.batchId(), record.productCode(), record.origin(),
                record.rowNo(), record.sourceRow(), record.testCaseId(), record.testCaseIdSource(),
                record.originalRecordId(), record.businessRecordId(), record.coreBusinessRecordId(),
                record.keyValue(), record.version(),
                json.toMap(record.dataJson()), texts.data(),
                texts.managed(),
                codec.tradeFormulas(record.formulaJson()), codec.managedFormulas(record.formulaJson()),
                codec.unresolvedFormulas(record.formulaJson()),
                toStringMap(json.toMapOrEmpty(record.extrasJson())),
                record.createdAt(), record.updatedAt());
    }

    public VerticalView vertical(String tenantId, String caseId) {
        TestCaseRecord record = require(tenantId, caseId);
        ProductSchema schema = products.require(tenantId, record.productCode());
        CellTexts texts = codec.texts(record);
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
        identity.add(new VerticalView.VerticalRow("coreBusinessRecordId", "Core Business Record Id",
                nullToEmpty(record.coreBusinessRecordId()), null, "TEXT", false));
        identity.add(new VerticalView.VerticalRow("sourceRow", "Source Row",
                record.sourceRow() == null ? "" : record.sourceRow().toString(), null, "INTEGER", false));

        List<VerticalView.VerticalRow> trade = new ArrayList<>();
        for (FieldDef field : schema.tradeFields()) {
            trade.add(new VerticalView.VerticalRow(field.key(), field.displayHeader(),
                    nullToEmpty(texts.trade(field.key())),
                    formulas.get(field.key()), field.type().name(), field.isKey()));
        }

        List<VerticalView.VerticalRow> managedRows = new ArrayList<>();
        texts.managed().forEach((key, value) -> managedRows.add(new VerticalView.VerticalRow(key, key, value,
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
        // only date / time / date time / boolean / numeric fields keep a normalized copy
        Map<String, Object> normalized = new LinkedHashMap<>();
        validation.values().forEach((key, value) -> {
            FieldDef field = schema.byKey().get(key);
            if (field != null && field.type().normalized() && value != null) {
                normalized.put(key, value);
            }
        });
        merged.putAll(normalized);

        Map<String, String> formulas = new LinkedHashMap<>(codec.tradeFormulas(record.formulaJson()));
        formulas.putAll(codec.managedFormulas(record.formulaJson()));
        applyFormulaChanges(schema, formulas, request.formulas());
        Map<String, String> managed = toStringMap(json.toMapOrEmpty(record.managedJson()));
        managed.putAll(request.managed());
        // a blank managed column is dropped, mirroring the bulk update semantics
        managed.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().isBlank());

        CellTexts rawTexts = mergeRawTexts(record, request);
        CellTexts texts = CellTexts.of(merged, managed, rawTexts);

        String businessRecordId = RecordIdGenerator.businessId(schema, texts, hashFormulas(formulas),
                properties.getIds().getHashLength());
        String coreBusinessRecordId = RecordIdGenerator.coreBusinessId(schema, texts, hashFormulas(formulas),
                properties.getIds().getHashLength());
        String keyValue = null;
        if (schema.uniqueField() != null) {
            keyValue = keyValue(schema, texts);
        }

        String searchText = buildSearchText(record.testCaseId(), keyValue, texts,
                toStringMap(json.toMapOrEmpty(record.extrasJson())));
        LocalDateTime now = time.nowUtc();
        int updated = repository.updateCase(tenantId, caseId, request.version(),
                codec.dataJson(schema, merged),
                codec.managedJson(managed),
                codec.rawJson(rawTexts),
                codec.formulaJson(schema, formulas, record.formulaJson()),
                formulas.size(), searchText, keyValue,
                businessRecordId.isEmpty() ? record.businessRecordId() : businessRecordId,
                coreBusinessRecordId.isEmpty() ? record.coreBusinessRecordId() : coreBusinessRecordId, now);
        if (updated == 0) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "The case was modified by someone else, reload and try again");
        }
        return detail(tenantId, caseId);
    }

    /**
     * Removes one case for good.
     *
     * <p>Deleting a case is not a reversible soft delete: the row and the references other rows hold on
     * it (clone ancestry, legacy dispatch links) are removed in the same transaction, so no dead id
     * stays behind.
     *
     * @param tenantId tenant scope
     * @param caseId   case to remove
     */
    @Transactional
    public void delete(String tenantId, String caseId) {
        require(tenantId, caseId);
        repository.purgeByIds(tenantId, List.of(caseId));
    }

    /**
     * Removes the selected cases for good, see {@link #delete(String, String)}.
     *
     * @param tenantId tenant scope
     * @param caseIds  cases to remove
     * @return number of removed cases
     */
    @Transactional
    public int bulkDelete(String tenantId, List<String> caseIds) {
        int limit = properties.getLimits().getMaxBulkDelete();
        if (caseIds == null || caseIds.isEmpty()) {
            throw ApiException.invalid("Select at least one case");
        }
        if (caseIds.size() > limit) {
            throw ApiException.limit("At most " + limit + " cases can be deleted at once");
        }
        return repository.purgeByIds(tenantId, caseIds);
    }

    /**
     * Rewrites one field of every case in scope, either with a literal value or with a formula.
     *
     * <p>The scope is the explicit selection, the current filter or the whole batch. Values are
     * parsed once with the field type and then applied to every row; the derived columns (business
     * record id, key value and search text) are recomputed per row. Updates overwrite concurrently
     * edited rows on purpose, so a bulk run is not blocked by a stale version.
     *
     * @param tenantId bound tenant
     * @param batchId  batch whose cases are updated
     * @param request  target field, mode, payload and scope
     * @return matched/updated counters plus a short before/after preview
     */
    @Transactional
    public BulkFieldUpdateResult bulkUpdateField(String tenantId, String batchId,
            BulkFieldUpdateRequest request) {
        BulkFieldUpdateRequest.Mode mode = request.resolveMode();
        String fieldKey = request.fieldKey() == null ? "" : request.fieldKey().trim();
        if (fieldKey.isEmpty()) {
            throw ApiException.invalid("Choose a field to update");
        }
        if (BULK_READ_ONLY_FIELDS.contains(fieldKey)) {
            throw ApiException.invalid("Field '" + fieldKey + "' is read only and cannot be updated in bulk");
        }
        int limit = properties.getLimits().getMaxBulkFieldUpdate();
        List<TestCaseRecord> records = bulkTargets(tenantId, batchId, request, limit);
        if (records.isEmpty()) {
            return new BulkFieldUpdateResult(fieldKey, mode.name(), request.dryRunEnabled(), 0, 0,
                    List.of(), List.of());
        }

        ProductSchema schema = products.require(tenantId, records.get(0).productCode());
        FieldDef field = schema.byKey().get(fieldKey);
        if (field == null) {
            throw ApiException.invalid("Field '" + fieldKey + "' does not exist in product "
                    + schema.code());
        }
        Object parsed = null;
        String rawValue = null;
        String formula = null;
        if (mode == BulkFieldUpdateRequest.Mode.VALUE) {
            String raw = resolveValueText(tenantId, request, BulkFieldUpdateRequest.Mode.VALUE);
            CaseValidator.CaseValidation validation = CaseValidator.validate(schema, Map.of(fieldKey, raw));
            if (!validation.valid()) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "The value does not fit field '" + field.header() + "'",
                        Map.of("issues", validation.issues()));
            }
            parsed = validation.values().get(fieldKey);
            if (field.required() && parsed == null) {
                throw ApiException.invalid("Required field '" + field.header() + "' cannot be cleared in bulk");
            }
            // the submitted text becomes the new original text of every touched row
            rawValue = raw == null || raw.isEmpty() ? null : raw;
        } else if (mode == BulkFieldUpdateRequest.Mode.FORMULA) {
            if (field.role() == FieldRole.MANAGED) {
                throw ApiException.invalid("Managed columns do not support formulas in a bulk update");
            }
            formula = requireFormula(resolveValueText(tenantId, request, BulkFieldUpdateRequest.Mode.FORMULA));
        } else if (request.fromSet()) {
            throw ApiException.invalid("Clearing a field does not use a meta set");
        }

        FieldDef uniqueField = schema.uniqueField();
        if (uniqueField != null && fieldKey.equals(uniqueField.key())) {
            if (mode == BulkFieldUpdateRequest.Mode.FORMULA) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "The key field '" + uniqueField.header() + "' cannot hold a formula");
            }
            if (records.size() > 1) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "Setting the key field '" + uniqueField.header() + "' on " + records.size()
                                + " cases would create duplicate keys");
            }
        }

        boolean dryRun = request.dryRunEnabled();
        String key = storageKey(schema, field);
        List<BulkFieldUpdateResult.Sample> samples = new ArrayList<>();
        List<BulkFieldUpdateResult.Issue> issues = new ArrayList<>();
        int updated = 0;
        for (TestCaseRecord record : records) {
            Map<String, Object> data = new LinkedHashMap<>(json.toMap(record.dataJson()));
            Map<String, String> managed = toStringMap(json.toMapOrEmpty(record.managedJson()));
            Map<String, String> formulas = new LinkedHashMap<>(codec.tradeFormulas(record.formulaJson()));
            formulas.putAll(codec.managedFormulas(record.formulaJson()));
            Map<String, String> rawData = new LinkedHashMap<>(codec.rawTexts(record.rawJson()).data());
            Map<String, String> rawManaged = new LinkedHashMap<>(codec.rawTexts(record.rawJson()).managed());
            String before = bulkDisplay(field, CellTexts.of(data, managed, new CellTexts(rawData, rawManaged)),
                    formulas, key);
            bulkApply(mode, field, data, managed, formulas, key, parsed, formula);
            applyRawText(mode, field, key, rawValue, rawData, rawManaged);
            CellTexts rawTexts = new CellTexts(rawData, rawManaged);
            CellTexts texts = CellTexts.of(data, managed, rawTexts);
            String after = bulkDisplay(field, texts, formulas, key);
            if (samples.size() < BULK_SAMPLES) {
                samples.add(new BulkFieldUpdateResult.Sample(record.id(), record.testCaseId(), record.rowNo(),
                        before, after));
            }
            if (dryRun) {
                continue;
            }

            String businessRecordId = RecordIdGenerator.businessId(schema, texts, hashFormulas(formulas),
                    properties.getIds().getHashLength());
            String coreBusinessRecordId = RecordIdGenerator.coreBusinessId(schema, texts, hashFormulas(formulas),
                    properties.getIds().getHashLength());
            String keyValue = uniqueField == null ? null : keyValue(schema, texts);
            String searchText = buildSearchText(record.testCaseId(), keyValue, texts,
                    toStringMap(json.toMapOrEmpty(record.extrasJson())));
            int changed = repository.updateCase(tenantId, record.id(), null,
                    codec.dataJson(schema, data),
                    codec.managedJson(managed),
                    codec.rawJson(rawTexts),
                    codec.formulaJson(schema, formulas, record.formulaJson()),
                    formulas.size(), searchText, keyValue,
                    businessRecordId.isEmpty() ? record.businessRecordId() : businessRecordId,
                    coreBusinessRecordId.isEmpty() ? record.coreBusinessRecordId() : coreBusinessRecordId,
                    time.nowUtc());
            if (changed == 0) {
                if (issues.size() < BULK_ISSUES) {
                    issues.add(new BulkFieldUpdateResult.Issue(record.id(), record.rowNo(), "ROW_MISSING",
                            "The case no longer exists"));
                }
            } else {
                updated++;
            }
        }
        return new BulkFieldUpdateResult(fieldKey, mode.name(), dryRun, records.size(), updated,
                samples, issues);
    }

    /** Resolves the cases a bulk update touches, honouring the selection / filter / batch scope. */
    private List<TestCaseRecord> bulkTargets(String tenantId, String batchId, BulkFieldUpdateRequest request,
            int limit) {
        BulkFieldUpdateRequest.Scope scope = request.resolveScope();
        if (scope == BulkFieldUpdateRequest.Scope.SELECTION) {
            List<TestCaseRecord> selected = new ArrayList<>();
            for (TestCaseRecord row : repository.findByIds(tenantId, request.caseIds())) {
                if ("ACTIVE".equals(row.status()) && batchId.equals(row.batchId())) {
                    selected.add(row);
                }
            }
            selected.sort(java.util.Comparator.comparingInt(TestCaseRecord::rowNo));
            if (selected.size() > limit) {
                throw ApiException.limit("At most " + limit + " cases can be updated at once");
            }
            return selected;
        }
        List<TestCaseRecord> rows = repository.listForBulkUpdate(tenantId, batchId, request.query(),
                request.hasFormula(), limit + 1);
        if (rows.size() > limit) {
            throw ApiException.limit("This scope matches more than " + limit
                    + " cases, narrow it with a search or a selection");
        }
        return rows;
    }

    /**
     * Original texts after a single case save: previous texts plus the exact strings the user
     * submitted.
     */
    private CellTexts mergeRawTexts(TestCaseRecord record, UpdateCaseRequest request) {
        CellTexts previous = codec.rawTexts(record.rawJson());
        Map<String, String> rawData = new LinkedHashMap<>(previous.data());
        Map<String, String> rawManaged = new LinkedHashMap<>(previous.managed());
        request.values().forEach((key, value) -> {
            if (value == null || value.isEmpty()) {
                rawData.remove(key);
            } else {
                rawData.put(key, value);
            }
        });
        request.managed().forEach((key, value) -> {
            if (value == null || value.isBlank()) {
                rawManaged.remove(key);
            } else {
                rawManaged.put(key, value);
            }
        });
        return new CellTexts(rawData, rawManaged);
    }

    /**
     * Keeps the original text of the bulk updated field in step with the new value.
     *
     * <p>The submitted text is stored verbatim, so a value typed as {@code 1,500,000.00} stays the
     * original text and the screens keep showing it. The normalized copy is maintained separately by
     * {@link #bulkApply}.
     */
    private void applyRawText(BulkFieldUpdateRequest.Mode mode, FieldDef field, String fieldKey,
            String rawText, Map<String, String> rawData, Map<String, String> rawManaged) {
        if (mode == BulkFieldUpdateRequest.Mode.FORMULA) {
            // the formula text lives in formula_json, the original text stays as it is
            return;
        }
        String text = mode == BulkFieldUpdateRequest.Mode.CLEAR || rawText == null || rawText.isBlank()
                ? null : rawText;
        if (field.role() == FieldRole.MANAGED) {
            if (text == null) {
                rawManaged.remove(fieldKey);
            } else {
                rawManaged.put(fieldKey, text);
            }
            return;
        }
        if (text == null) {
            rawData.remove(fieldKey);
        } else {
            rawData.put(fieldKey, text);
        }
    }

    /** Unique key text of one row, taken from the original text of the key field. */
    private String keyValue(ProductSchema schema, CellTexts texts) {
        FieldDef unique = schema.uniqueField();
        if (unique == null) {
            return null;
        }
        String text = texts.trade(unique.key());
        return text == null || text.isBlank() ? null : FieldCodec.canonical(unique, text);
    }

    /** Current display text of the target field, used for the before/after preview. */
    private String bulkDisplay(FieldDef field, CellTexts texts, Map<String, String> formulas, String key) {
        String formula = formulas.get(key);
        if (formula != null) {
            return "\u0192 " + formula;
        }
        if (field.role() == FieldRole.MANAGED) {
            return nullToEmpty(texts.managedValue(key));
        }
        return nullToEmpty(texts.trade(key));
    }

    /** Applies one mode to the in-memory representation of a single case. */
    private void bulkApply(BulkFieldUpdateRequest.Mode mode, FieldDef field, Map<String, Object> data,
            Map<String, String> managed, Map<String, String> formulas, String key, Object parsed,
            String formula) {
        boolean managedField = field.role() == FieldRole.MANAGED;
        String text = parsed == null ? null : FieldCodec.format(field, parsed);
        switch (mode) {
            case VALUE -> {
                formulas.remove(key);
                if (managedField) {
                    if (text == null) {
                        managed.remove(key);
                    } else {
                        managed.put(key, text);
                    }
                } else if (parsed == null || !field.type().normalized()) {
                    // text and enumerated values live in the original text only
                    data.remove(key);
                } else {
                    data.put(key, parsed);
                }
            }
            case FORMULA -> formulas.put(key, formula);
            case CLEAR -> {
                formulas.remove(key);
                if (managedField) {
                    managed.remove(key);
                } else {
                    data.remove(key);
                }
            }
            default -> throw ApiException.invalid("Unknown update mode " + mode);
        }
    }

    /**
     * Applies the formula edits of a single case save.
     *
     * <p>A blank text removes the formula of that field, a non blank one replaces it. Managed
     * columns and the unique key field never take a formula, matching the bulk update rules.
     */
    private void applyFormulaChanges(ProductSchema schema, Map<String, String> formulas,
            Map<String, String> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }
        FieldDef uniqueField = schema.uniqueField();
        for (Map.Entry<String, String> entry : changes.entrySet()) {
            String fieldKey = entry.getKey() == null ? "" : entry.getKey().trim();
            FieldDef field = schema.byKey().get(fieldKey);
            if (field == null) {
                throw ApiException.invalid("Field '" + fieldKey + "' does not exist in product "
                        + schema.code());
            }
            String text = entry.getValue() == null ? "" : entry.getValue().trim();
            if (text.isEmpty()) {
                formulas.remove(fieldKey);
                continue;
            }
            if (field.role() == FieldRole.MANAGED) {
                throw ApiException.invalid("Managed column '" + field.header()
                        + "' does not support formulas");
            }
            if (uniqueField != null && fieldKey.equals(uniqueField.key())) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "The key field '" + uniqueField.header() + "' cannot hold a formula");
            }
            formulas.put(fieldKey, requireFormula(text));
        }
    }

    /** Validates formula text entered by the user before it reaches the workbook recalculator. */
    private static String requireFormula(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            throw ApiException.invalid("Enter the formula, for example =D2*E2");
        }
        if (!text.startsWith("=")) {
            throw ApiException.invalid("A formula must start with '='");
        }
        if (text.length() < 2) {
            throw ApiException.invalid("The formula body is empty");
        }
        if (text.length() > BULK_FORMULA_LENGTH) {
            throw ApiException.invalid("The formula exceeds " + BULK_FORMULA_LENGTH + " characters");
        }
        return text;
    }

    /**
     * Resolves the text of the update, either the raw input or the content of a meta set item.
     *
     * <p>A set is only accepted when its kind matches the mode, so value sets cannot fill a formula
     * and vice versa. The item must exist and be enabled; its content wins over its code.
     */
    private String resolveValueText(String tenantId, BulkFieldUpdateRequest request,
            BulkFieldUpdateRequest.Mode mode) {
        if (!request.fromSet()) {
            return mode == BulkFieldUpdateRequest.Mode.FORMULA ? request.formula() : request.value();
        }
        String setCode = request.valueSet().trim().toUpperCase(java.util.Locale.ROOT);
        MetaRepository.MetaSetRow set = metaStore.set(tenantId, setCode)
                .orElseThrow(() -> ApiException.notFound("Meta set '" + setCode + "' does not exist"));
        String expected = mode == BulkFieldUpdateRequest.Mode.FORMULA ? MetaSetType.FORMULA.name()
                : MetaSetType.VALUE.name();
        if (!expected.equals(set.setType())) {
            throw ApiException.invalid("Meta set '" + setCode + "' holds "
                    + set.setType().toLowerCase(java.util.Locale.ROOT) + "s, not "
                    + expected.toLowerCase(java.util.Locale.ROOT) + "s");
        }
        String itemCode = request.valueItem() == null ? "" : request.valueItem().trim().toUpperCase(
                java.util.Locale.ROOT);
        if (itemCode.isEmpty()) {
            throw ApiException.invalid("Choose an item of meta set '" + setCode + "'");
        }
        MetaRepository.MetaItemRow item = metaStore.item(tenantId, setCode, itemCode)
                .filter(MetaRepository.MetaItemRow::enabled)
                .orElseThrow(() -> ApiException.invalid(
                        "Item '" + itemCode + "' is not an enabled item of meta set '" + setCode + "'"));
        String content = item.content() == null || item.content().isBlank() ? item.code() : item.content();
        return content;
    }

    private CaseSummary toSummary(TestCaseRecord record, ProductSchema schema) {
        CellTexts texts = codec.texts(record);
        Map<String, String> formulas = codec.tradeFormulas(record.formulaJson());
        List<CaseSummary.PreviewField> preview = new ArrayList<>();
        for (FieldDef field : schema.tradeFields()) {
            if (preview.size() >= PREVIEW_FIELDS) {
                break;
            }
            preview.add(new CaseSummary.PreviewField(field.displayHeader(),
                    nullToEmpty(texts.trade(field.key())),
                    formulas.containsKey(field.key())));
        }
        return new CaseSummary(record.id(), record.batchId(), record.productCode(), record.origin(),
                record.rowNo(), record.testCaseId(), record.originalRecordId(), record.businessRecordId(),
                record.coreBusinessRecordId(), record.keyValue(), record.formulaCount(), record.version(),
                preview);
    }

    /** Normalizes formula references only when the configured identity policy asks for it. */
    private Map<String, String> hashFormulas(Map<String, String> formulas) {
        return properties.getIds().isNormalizeFormulaRefs() ? Formulas.normalized(formulas) : formulas;
    }

    private String buildSearchText(String testCaseId, String keyValue, CellTexts texts,
            Map<String, String> extras) {
        StringBuilder sb = new StringBuilder();
        append(sb, testCaseId);
        append(sb, keyValue);
        texts.data().values().forEach(value -> append(sb, value));
        texts.managed().values().forEach(value -> append(sb, value));
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
        // the original texts carry every field, the normalized copies only a subset
        CellTexts texts = codec.texts(record);
        Map<String, String> raw = new LinkedHashMap<>(texts.managed());
        raw.putAll(texts.data());
        return CaseValidator.validate(schema, raw).issues();
    }

    // ------------------------------------------------------- transient overrides

    /**
     * Resolved form of a transient override, ready to be applied in memory.
     *
     * @param field   target field definition
     * @param mode    how the field is rewritten
     * @param value   parsed value for {@code VALUE}, otherwise {@code null}
     * @param rawText the text to store as the new original text for {@code VALUE}, otherwise {@code null}
     * @param formula formula text for {@code FORMULA}, otherwise {@code null}
     */
    public record ResolvedOverride(FieldDef field, BulkFieldUpdateRequest.Mode mode, Object value,
            String rawText, String formula) {

        public String fieldKey() {
            return field.key();
        }
    }

    /**
     * Validates one transient override against the product schema without touching the database.
     *
     * <p>The value rules are identical to a bulk update: the payload is parsed with the field type,
     * a set item is only accepted when the set kind matches the mode, and required fields cannot be
     * cleared.
     */
    public ResolvedOverride resolveOverride(String tenantId, ProductSchema schema, FieldOverride override) {
        BulkFieldUpdateRequest request = override.toBulkRequest();
        BulkFieldUpdateRequest.Mode mode = request.resolveMode();
        String fieldKey = request.fieldKey() == null ? "" : request.fieldKey().trim();
        if (fieldKey.isEmpty()) {
            throw ApiException.invalid("Choose a field to override");
        }
        if (BULK_READ_ONLY_FIELDS.contains(fieldKey)) {
            throw ApiException.invalid("Field '" + fieldKey + "' is read only and cannot be overridden");
        }
        FieldDef field = schema.byKey().get(fieldKey);
        if (field == null) {
            throw ApiException.invalid("Field '" + fieldKey + "' does not exist in product " + schema.code());
        }
        Object parsed = null;
        String rawText = null;
        String formula = null;
        if (mode == BulkFieldUpdateRequest.Mode.VALUE) {
            String raw = resolveValueText(tenantId, request, BulkFieldUpdateRequest.Mode.VALUE);
            CaseValidator.CaseValidation validation = CaseValidator.validate(schema, Map.of(fieldKey, raw));
            if (!validation.valid()) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "The value does not fit field '" + field.header() + "'",
                        Map.of("issues", validation.issues()));
            }
            parsed = validation.values().get(fieldKey);
            if (field.required() && parsed == null) {
                throw ApiException.invalid("Required field '" + field.header() + "' cannot be cleared");
            }
            // the text the caller submitted becomes the new original text of the field
            rawText = raw == null || raw.isEmpty() ? null : raw;
        } else if (mode == BulkFieldUpdateRequest.Mode.FORMULA) {
            if (field.role() == FieldRole.MANAGED) {
                throw ApiException.invalid("Managed columns do not support formulas");
            }
            formula = requireFormula(resolveValueText(tenantId, request, BulkFieldUpdateRequest.Mode.FORMULA));
        } else if (request.fromSet()) {
            throw ApiException.invalid("Clearing a field does not use a meta set");
        }
        return new ResolvedOverride(field, mode, parsed, rawText, formula);
    }

    /**
     * Applies a resolved override to the in-memory maps of one case; nothing is persisted.
     *
     * @param rawData    trade field key to original text
     * @param rawManaged managed storage key to original text
     */
    public void applyOverride(ResolvedOverride resolved, ProductSchema schema, Map<String, Object> data,
            Map<String, String> managed, Map<String, String> formulas, Map<String, String> rawData,
            Map<String, String> rawManaged) {
        String key = storageKey(schema, resolved.field());
        bulkApply(resolved.mode(), resolved.field(), data, managed, formulas, key,
                resolved.value(), resolved.formula());
        applyRawText(resolved.mode(), resolved.field(), key, resolved.rawText(), rawData, rawManaged);
    }

    /**
     * Key used by the value maps: managed columns live under their storage key ({@code _Remarks}),
     * trade fields under their field key.
     */
    private static String storageKey(ProductSchema schema, FieldDef field) {
        if (field.role() != FieldRole.MANAGED) {
            return field.key();
        }
        return ManagedColumnClassifier.storageKeys(schema.fields())
                .getOrDefault(field.key(), field.key());
    }

    /** Text shown for a resolved override in the export record, for instance {@code ƒ =D2*E2}. */
    public String overrideDisplay(ResolvedOverride resolved) {
        return switch (resolved.mode()) {
            case FORMULA -> "\u0192 " + resolved.formula();
            case CLEAR -> "";
            case VALUE -> resolved.value() == null ? "" : FieldCodec.format(resolved.field(), resolved.value());
        };
    }
}
