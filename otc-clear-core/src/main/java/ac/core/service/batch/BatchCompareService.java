package ac.core.service.batch;

import ac.core.common.ApiException;
import ac.core.common.JsonSupport;
import ac.core.persistence.ImportBatchRepository;
import ac.core.persistence.ImportBatchRow;
import ac.core.persistence.TestCaseRecord;
import ac.core.persistence.TestCaseRepository;
import ac.core.service.cases.CaseJsonCodec;
import ac.core.service.product.ProductCatalog;
import ac.domain.casefile.CellTexts;
import ac.domain.product.FieldDef;
import ac.domain.product.FieldRole;
import ac.domain.product.ProductSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Read-only comparison of two batches.
 *
 * <p>Field names come from the product definitions of the two batches, values and formulas from the
 * stored case rows. Nothing is persisted: the result is rendered by the caller and thrown away.
 */
@Service
public class BatchCompareService {

    /** Comparison mode. */
    public enum Mode {
        /** Compare the field lists only (presence, and order when requested). */
        FIELDS,
        /** Compare the field lists and every case value, formulas included. */
        VALUES;

        static Mode of(String raw) {
            if (raw == null || raw.isBlank()) {
                return VALUES;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ApiException.invalid("Compare mode must be FIELDS or VALUES");
            }
        }
    }

    /** How the case rows of the two batches are matched. */
    public enum Align {
        /** Match by the business key value, falling back to the row number when it is blank. */
        KEY,
        /** Match by the row number. */
        ROW,
        /** Match by the test case id. */
        TESTCASE;

        static Align of(String raw) {
            if (raw == null || raw.isBlank()) {
                return KEY;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ApiException.invalid("Align must be KEY, ROW or TESTCASE");
            }
        }
    }

    /** Upper bound of compared cases per side, keeps the response and the browser light. */
    static final int MAX_CASES = 1000;

    /**
     * One requested comparison.
     *
     * @param rightBatchId batch compared against the one in the path
     * @param mode         {@code FIELDS} or {@code VALUES}, {@code null} means {@code VALUES}
     * @param align        {@code KEY}, {@code ROW} or {@code TESTCASE}, {@code null} means {@code KEY}
     * @param compareOrder true when field and row order differences count as differences
     */
    public record Request(String rightBatchId, String mode, String align, Boolean compareOrder) {
    }

    /**
     * Batch header of one side.
     *
     * @param id          batch id
     * @param name        batch name
     * @param productCode product code
     * @param productName product display name
     * @param caseCount   active case rows
     */
    public record Side(String id, String name, String productCode, String productName, int caseCount) {
    }

    /**
     * One field of the union of both products.
     *
     * @param fieldKey   internal field key
     * @param leftHeader header in the left product, {@code null} when missing
     * @param rightHeader header in the right product, {@code null} when missing
     * @param leftIndex  1-based position in the left product, 0 when missing
     * @param rightIndex 1-based position in the right product, 0 when missing
     * @param status     {@code SAME}, {@code LEFT_ONLY}, {@code RIGHT_ONLY} or {@code MOVED}
     */
    public record FieldDiff(String fieldKey, String leftHeader, String rightHeader, int leftIndex,
            int rightIndex, String status) {
    }

    /**
     * One differing field value of a matched case.
     *
     * @param fieldKey    internal field key
     * @param header      display header
     * @param leftValue   value on the left, {@code null} when absent
     * @param rightValue  value on the right, {@code null} when absent
     * @param leftFormula formula on the left, {@code null} when the value is static
     * @param rightFormula formula on the right, {@code null} when the value is static
     */
    public record ValueDiff(String fieldKey, String header, String leftValue, String rightValue,
            String leftFormula, String rightFormula) {
    }

    /**
     * One compared case.
     *
     * @param key      alignment key of the row
     * @param leftRow  row number on the left, 0 when the row only exists on the right
     * @param rightRow row number on the right, 0 when the row only exists on the left
     * @param status   {@code SAME}, {@code DIFFERENT}, {@code MOVED}, {@code LEFT_ONLY}, {@code RIGHT_ONLY}
     * @param values   differing values, empty when the row matches
     */
    public record CaseDiff(String key, int leftRow, int rightRow, String status, List<ValueDiff> values) {
        public CaseDiff {
            values = List.copyOf(values);
        }
    }

    /**
     * Comparison result.
     *
     * @param left         left batch header
     * @param right        right batch header
     * @param mode         resolved mode
     * @param align        resolved alignment
     * @param compareOrder whether order differences were reported
     * @param fields       union of the fields
     * @param cases        compared cases, empty in {@code FIELDS} mode
     * @param summary      counters shown at the top of the panel
     * @param truncated    true when only the first {@link #MAX_CASES} cases were compared
     */
    public record Result(Side left, Side right, String mode, String align, boolean compareOrder,
            List<FieldDiff> fields, List<CaseDiff> cases, Summary summary, boolean truncated) {
        public Result {
            fields = List.copyOf(fields);
            cases = List.copyOf(cases);
        }
    }

    /**
     * Counters of one comparison.
     *
     * @param fieldsCompared  fields present on both sides
     * @param fieldsDifferent fields that differ (moved or only on one side)
     * @param fieldsLeftOnly  fields only the left product declares
     * @param fieldsRightOnly fields only the right product declares
     * @param casesCompared   matched cases
     * @param casesSame       matched cases without differences
     * @param casesDifferent  matched cases with at least one difference
     * @param casesLeftOnly   cases only the left batch holds
     * @param casesRightOnly  cases only the right batch holds
     * @param orderDifferent  true when the compared sequence of keys differs
     */
    public record Summary(int fieldsCompared, int fieldsDifferent, int fieldsLeftOnly, int fieldsRightOnly,
            int casesCompared, int casesSame, int casesDifferent, int casesLeftOnly, int casesRightOnly,
            boolean orderDifferent) {
    }

    private final ImportBatchRepository batchRepository;
    private final TestCaseRepository caseRepository;
    private final ProductCatalog products;
    private final JsonSupport json;
    private final CaseJsonCodec codec;

    public BatchCompareService(ImportBatchRepository batchRepository, TestCaseRepository caseRepository,
            ProductCatalog products, JsonSupport json, CaseJsonCodec codec) {
        this.batchRepository = batchRepository;
        this.caseRepository = caseRepository;
        this.products = products;
        this.json = json;
        this.codec = codec;
    }

    /** Compares two batches of one tenant; nothing is stored. */
    public Result compare(String tenantId, String leftBatchId, Request request) {
        if (request == null || request.rightBatchId() == null || request.rightBatchId().isBlank()) {
            throw ApiException.invalid("Choose the batch to compare against");
        }
        if (leftBatchId.equals(request.rightBatchId())) {
            throw ApiException.invalid("Choose two different batches");
        }
        ImportBatchRow left = require(tenantId, leftBatchId);
        ImportBatchRow right = require(tenantId, request.rightBatchId());
        Mode mode = Mode.of(request.mode());
        Align align = Align.of(request.align());
        boolean compareOrder = Boolean.TRUE.equals(request.compareOrder());

        ProductSchema leftSchema = products.require(tenantId, left.productCode());
        ProductSchema rightSchema = products.require(tenantId, right.productCode());
        List<FieldDiff> fields = fields(leftSchema, rightSchema, compareOrder);

        List<TestCaseRecord> leftRows = caseRepository.listForExport(tenantId, leftBatchId);
        List<TestCaseRecord> rightRows = caseRepository.listForExport(tenantId, right.id());
        boolean truncated = leftRows.size() > MAX_CASES || rightRows.size() > MAX_CASES;
        int leftCaseCount = leftRows.size();
        int rightCaseCount = rightRows.size();
        if (truncated) {
            leftRows = leftRows.subList(0, Math.min(MAX_CASES, leftRows.size()));
            rightRows = rightRows.subList(0, Math.min(MAX_CASES, rightRows.size()));
        }

        List<CaseDiff> cases = mode == Mode.FIELDS
                ? List.of()
                : cases(leftRows, rightRows, align, compareOrder, unionFields(leftSchema, rightSchema),
                        leftSchema, rightSchema);

        // the side panels show the real batch size, not the number of rows that were compared
        return new Result(side(left, leftSchema, leftCaseCount), side(right, rightSchema, rightCaseCount),
                mode.name(), align.name(), compareOrder, fields, cases,
                summary(fields, cases, compareOrder), truncated);
    }

    private ImportBatchRow require(String tenantId, String batchId) {
        return batchRepository.find(tenantId, batchId)
                .orElseThrow(() -> ApiException.notFound("Batch " + batchId + " does not exist"));
    }

    private static Side side(ImportBatchRow row, ProductSchema schema, int caseCount) {
        return new Side(row.id(), row.name(), row.productCode(), schema.name(), caseCount);
    }

    /** Union of both field lists, left order first, right-only fields appended. */
    private static List<String> unionFields(ProductSchema left, ProductSchema right) {
        Set<String> keys = new LinkedHashSet<>();
        left.fields().forEach(field -> keys.add(field.key()));
        right.fields().forEach(field -> keys.add(field.key()));
        return List.copyOf(keys);
    }

    private static List<FieldDiff> fields(ProductSchema left, ProductSchema right, boolean compareOrder) {
        List<FieldDiff> diffs = new ArrayList<>();
        List<String> keys = unionFields(left, right);
        for (String key : keys) {
            FieldDef leftField = left.byKey().get(key);
            FieldDef rightField = right.byKey().get(key);
            int leftIndex = leftField == null ? 0 : indexOf(left, key);
            int rightIndex = rightField == null ? 0 : indexOf(right, key);
            String status;
            if (leftField == null) {
                status = "RIGHT_ONLY";
            } else if (rightField == null) {
                status = "LEFT_ONLY";
            } else if (compareOrder && leftIndex != rightIndex) {
                status = "MOVED";
            } else {
                status = "SAME";
            }
            diffs.add(new FieldDiff(key,
                    leftField == null ? null : leftField.header(),
                    rightField == null ? null : rightField.header(),
                    leftIndex, rightIndex, status));
        }
        return diffs;
    }

    private static int indexOf(ProductSchema schema, String key) {
        for (int i = 0; i < schema.fields().size(); i++) {
            if (schema.fields().get(i).key().equals(key)) {
                return i + 1;
            }
        }
        return 0;
    }

    private List<CaseDiff> cases(List<TestCaseRecord> leftRows, List<TestCaseRecord> rightRows, Align align,
            boolean compareOrder, List<String> fieldKeys, ProductSchema leftSchema, ProductSchema rightSchema) {
        Map<String, TestCaseRecord> rightByKey = new LinkedHashMap<>();
        for (TestCaseRecord row : rightRows) {
            rightByKey.putIfAbsent(alignKey(row, align), row);
        }
        Map<String, Integer> rightPositions = new LinkedHashMap<>();
        for (int i = 0; i < rightRows.size(); i++) {
            rightPositions.putIfAbsent(alignKey(rightRows.get(i), align), i + 1);
        }

        List<CaseDiff> diffs = new ArrayList<>();
        Set<String> matched = new LinkedHashSet<>();
        Set<String> listed = new LinkedHashSet<>();
        for (int i = 0; i < leftRows.size(); i++) {
            TestCaseRecord leftRow = leftRows.get(i);
            String key = alignKey(leftRow, align);
            TestCaseRecord rightRow = rightByKey.get(key);
            if (rightRow == null) {
                diffs.add(new CaseDiff(key, leftRow.rowNo(), 0, "LEFT_ONLY", List.of()));
                continue;
            }
            matched.add(key);
            listed.add(key);
            List<ValueDiff> values = values(leftRow, rightRow, fieldKeys, leftSchema, rightSchema);
            int rightPosition = rightPositions.getOrDefault(key, i + 1);
            String status;
            if (!values.isEmpty()) {
                status = "DIFFERENT";
            } else if (compareOrder && rightPosition != i + 1) {
                status = "MOVED";
            } else {
                status = "SAME";
            }
            diffs.add(new CaseDiff(key, leftRow.rowNo(), rightRow.rowNo(), status, values));
        }
        for (TestCaseRecord rightRow : rightRows) {
            String key = alignKey(rightRow, align);
            // the key set keeps this loop linear; comparing against every diff made it quadratic
            if (!matched.contains(key) && listed.add(key)) {
                diffs.add(new CaseDiff(key, 0, rightRow.rowNo(), "RIGHT_ONLY", List.of()));
            }
        }
        return diffs;
    }

    /** Alignment key of one row, {@code #rowNo} when the requested key is blank. */
    private static String alignKey(TestCaseRecord row, Align align) {
        String key = switch (align) {
            case ROW -> null;
            case TESTCASE -> row.testCaseId();
            case KEY -> row.keyValue();
        };
        if (key == null || key.isBlank()) {
            return "#" + row.rowNo();
        }
        return key.trim();
    }

    /**
     * Field by field diff of one matched pair of cases.
     *
     * <p>Values are compared the same way as the case comparison: trade fields and managed columns
     * use the original cell text recorded at import time, and equality is byte equality, so a value
     * that only differs in formatting counts as a difference.
     */
    private List<ValueDiff> values(TestCaseRecord left, TestCaseRecord right, List<String> fieldKeys,
            ProductSchema leftSchema, ProductSchema rightSchema) {
        Map<String, Object> leftData = json.toMapOrEmpty(left.dataJson());
        Map<String, Object> rightData = json.toMapOrEmpty(right.dataJson());
        Map<String, Object> leftManaged = json.toMapOrEmpty(left.managedJson());
        Map<String, Object> rightManaged = json.toMapOrEmpty(right.managedJson());
        CellTexts leftTexts = codec.texts(left);
        CellTexts rightTexts = codec.texts(right);
        Map<String, String> leftFormulas = formulas(left.formulaJson());
        Map<String, String> rightFormulas = formulas(right.formulaJson());
        List<String> keys = new ArrayList<>(fieldKeys);
        for (String managedKey : unionManagedKeys(leftManaged, rightManaged)) {
            if (!keys.contains(managedKey)) {
                keys.add(managedKey);
            }
        }
        List<ValueDiff> diffs = new ArrayList<>();
        for (String fieldKey : keys) {
            if (SKIPPED_KEYS.contains(fieldKey)) {
                continue;
            }
            FieldDef leftField = leftSchema.byKey().get(fieldKey);
            FieldDef rightField = rightSchema.byKey().get(fieldKey);
            boolean managed = isManaged(leftField) || isManaged(rightField)
                    || (leftField == null && rightField == null);
            String leftValue;
            String rightValue;
            boolean same;
            if (managed) {
                leftValue = CaseJsonCodec.textOrNull(leftTexts.managedValue(fieldKey));
                rightValue = CaseJsonCodec.textOrNull(rightTexts.managedValue(fieldKey));
                same = Objects.equals(leftValue, rightValue);
            } else {
                leftValue = leftField == null ? null : CaseJsonCodec.textOrNull(leftTexts.trade(fieldKey));
                rightValue = rightField == null ? null : CaseJsonCodec.textOrNull(rightTexts.trade(fieldKey));
                same = Objects.equals(leftValue, rightValue);
            }
            String leftFormula = leftFormulas.get(fieldKey);
            String rightFormula = rightFormulas.get(fieldKey);
            if (same && Objects.equals(leftFormula, rightFormula)) {
                continue;
            }
            diffs.add(new ValueDiff(fieldKey, header(leftSchema, rightSchema, fieldKey), leftValue, rightValue,
                    leftFormula, rightFormula));
        }
        return diffs;
    }

    private static boolean isManaged(FieldDef field) {
        return field != null && field.role() == FieldRole.MANAGED;
    }

    /** Union of the managed column keys of both rows, left order first. */
    private static List<String> unionManagedKeys(Map<String, Object> left, Map<String, Object> right) {
        Set<String> keys = new LinkedHashSet<>(left.keySet());
        keys.addAll(right.keySet());
        return List.copyOf(keys);
    }

    /** Internal bookkeeping columns that never carry business meaning. */
    private static final Set<String> SKIPPED_KEYS = Set.of("originalRecordId", "businessRecordId", "searchText");

    private static String header(ProductSchema left, ProductSchema right, String fieldKey) {
        FieldDef leftField = left.byKey().get(fieldKey);
        if (leftField != null) {
            return leftField.header();
        }
        FieldDef rightField = right.byKey().get(fieldKey);
        return rightField == null ? fieldKey : rightField.header();
    }

    /** Formulas of one case row, trade and managed columns merged. */
    private Map<String, String> formulas(String formulaJson) {
        Map<String, String> formulas = new LinkedHashMap<>();
        if (formulaJson == null || formulaJson.isBlank()) {
            return formulas;
        }
        Map<String, Object> payload = json.toMapOrEmpty(formulaJson);
        collectFormulas(payload.get("fields"), formulas);
        collectFormulas(payload.get("managed"), formulas);
        return formulas;
    }

    private void collectFormulas(Object node, Map<String, String> target) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (key != null && value != null) {
                    target.put(String.valueOf(key), String.valueOf(value));
                }
            });
        }
    }

    private static Summary summary(List<FieldDiff> fields, List<CaseDiff> cases, boolean compareOrder) {
        int fieldsCompared = 0;
        int fieldsDifferent = 0;
        int fieldsLeftOnly = 0;
        int fieldsRightOnly = 0;
        for (FieldDiff field : fields) {
            switch (field.status()) {
                case "LEFT_ONLY" -> fieldsLeftOnly++;
                case "RIGHT_ONLY" -> fieldsRightOnly++;
                default -> {
                    fieldsCompared++;
                    if (!"SAME".equals(field.status())) {
                        fieldsDifferent++;
                    }
                }
            }
        }
        int casesCompared = 0;
        int casesSame = 0;
        int casesDifferent = 0;
        int casesLeftOnly = 0;
        int casesRightOnly = 0;
        boolean orderDifferent = false;
        for (CaseDiff one : cases) {
            switch (one.status()) {
                case "LEFT_ONLY" -> casesLeftOnly++;
                case "RIGHT_ONLY" -> casesRightOnly++;
                case "MOVED" -> {
                    casesCompared++;
                    casesDifferent++;
                    orderDifferent = true;
                }
                default -> {
                    casesCompared++;
                    if ("SAME".equals(one.status())) {
                        casesSame++;
                    } else {
                        casesDifferent++;
                    }
                }
            }
        }
        return new Summary(fieldsCompared, fieldsDifferent + fieldsLeftOnly + fieldsRightOnly, fieldsLeftOnly,
                fieldsRightOnly, casesCompared, casesSame, casesDifferent, casesLeftOnly, casesRightOnly,
                compareOrder && orderDifferent);
    }
}
