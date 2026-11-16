package ac.core.service.cases;

import ac.core.common.ApiException;
import ac.core.common.JsonSupport;
import ac.core.persistence.ImportBatchRepository;
import ac.core.persistence.ImportBatchRow;
import ac.core.persistence.TestCaseRecord;
import ac.core.persistence.TestCaseRepository;
import ac.core.service.product.ProductCatalog;
import ac.domain.casefile.CellTexts;
import ac.domain.product.FieldDef;
import ac.domain.product.ProductSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Read-only comparison of two test cases, possibly from different batches, tasks and products.
 *
 * <p>Fields come from the product definitions of both cases, values and formulas from the stored
 * rows. Nothing is persisted: the result is rendered by the caller and thrown away.
 */
@Service
public class CaseCompareService {

    /** One requested comparison. */
    public record Request(String rightCaseId) {
    }

    /**
     * Header of one compared case.
     *
     * @param caseId      case id
     * @param testCaseId  test case id, may be generated
     * @param keyValue    value of the unique key field, {@code null} when the product has none
     * @param batchId     owning batch
     * @param batchName   owning batch name
     * @param productCode product code
     * @param productName product display name
     * @param rowNo       row number inside the batch
     * @param sourceRow   row number inside the source sheet, {@code null} for cloned rows
     */
    public record Side(String caseId, String testCaseId, String keyValue, String batchId, String batchName,
            String productCode, String productName, int rowNo, Integer sourceRow) {
    }

    /**
     * One field of the union of both products.
     *
     * @param fieldKey     internal field key
     * @param header       display header, taken from the left product when both declare it
     * @param scope        {@code TRADE} for business columns, {@code MANAGED} for managed columns
     * @param leftValue    display value on the left, {@code null} when the field is absent there
     * @param rightValue   display value on the right, {@code null} when the field is absent there
     * @param leftFormula  formula text on the left, {@code null} when the value is static
     * @param rightFormula formula text on the right, {@code null} when the value is static
     * @param status       {@code SAME}, {@code DIFFERENT}, {@code LEFT_ONLY} or {@code RIGHT_ONLY}
     */
    public record FieldDiff(String fieldKey, String header, String scope, String leftValue, String rightValue,
            String leftFormula, String rightFormula, String status) {
    }

    /**
     * Counters of one comparison.
     *
     * @param fieldsCompared   fields declared by both products
     * @param fieldsSame       compared fields without value or formula differences
     * @param fieldsDifferent  compared fields with at least one difference
     * @param fieldsLeftOnly   fields only the left product declares
     * @param fieldsRightOnly  fields only the right product declares
     * @param formulaDifferent fields whose formula text differs
     */
    public record Summary(int fieldsCompared, int fieldsSame, int fieldsDifferent, int fieldsLeftOnly,
            int fieldsRightOnly, int formulaDifferent) {
    }

    /**
     * Comparison result.
     *
     * @param left    left case header
     * @param right   right case header
     * @param fields  union of both field lists, left order first
     * @param summary counters shown at the top of the panel
     */
    public record Result(Side left, Side right, List<FieldDiff> fields, Summary summary) {
        public Result {
            fields = List.copyOf(fields);
        }
    }

    private final TestCaseRepository caseRepository;
    private final ImportBatchRepository batchRepository;
    private final ProductCatalog products;
    private final CaseJsonCodec codec;
    private final JsonSupport json;

    public CaseCompareService(TestCaseRepository caseRepository, ImportBatchRepository batchRepository,
            ProductCatalog products, CaseJsonCodec codec, JsonSupport json) {
        this.caseRepository = caseRepository;
        this.batchRepository = batchRepository;
        this.products = products;
        this.codec = codec;
        this.json = json;
    }

    /** Compares two cases of one tenant; nothing is stored. */
    public Result compare(String tenantId, String leftCaseId, Request request) {
        if (request == null || request.rightCaseId() == null || request.rightCaseId().isBlank()) {
            throw ApiException.invalid("Choose the case to compare against");
        }
        String rightCaseId = request.rightCaseId().trim();
        if (leftCaseId.equals(rightCaseId)) {
            throw ApiException.invalid("Choose two different cases");
        }
        TestCaseRecord left = require(tenantId, leftCaseId);
        TestCaseRecord right = require(tenantId, rightCaseId);
        ProductSchema leftSchema = products.require(tenantId, left.productCode());
        ProductSchema rightSchema = products.require(tenantId, right.productCode());

        Map<String, Object> leftData = json.toMapOrEmpty(left.dataJson());
        Map<String, Object> rightData = json.toMapOrEmpty(right.dataJson());
        Map<String, Object> leftManaged = json.toMapOrEmpty(left.managedJson());
        Map<String, Object> rightManaged = json.toMapOrEmpty(right.managedJson());
        CellTexts leftTexts = codec.texts(left);
        CellTexts rightTexts = codec.texts(right);
        Map<String, String> leftFormulas = formulas(left.formulaJson());
        Map<String, String> rightFormulas = formulas(right.formulaJson());

        List<FieldDiff> fields = new ArrayList<>();
        for (String fieldKey : unionTradeFields(leftSchema, rightSchema)) {
            FieldDef leftField = leftSchema.byKey().get(fieldKey);
            FieldDef rightField = rightSchema.byKey().get(fieldKey);
            FieldDef field = leftField == null ? rightField : leftField;
            String leftValue = leftField == null ? null : CaseJsonCodec.textOrNull(leftTexts.trade(fieldKey));
            String rightValue = rightField == null ? null : CaseJsonCodec.textOrNull(rightTexts.trade(fieldKey));
            String leftFormula = leftFormulas.get(fieldKey);
            String rightFormula = rightFormulas.get(fieldKey);
            String status;
            if (leftField == null) {
                status = "RIGHT_ONLY";
            } else if (rightField == null) {
                status = "LEFT_ONLY";
            } else if (Objects.equals(leftValue, rightValue) && Objects.equals(leftFormula, rightFormula)) {
                status = "SAME";
            } else {
                status = "DIFFERENT";
            }
            fields.add(new FieldDiff(fieldKey, field.header(), "TRADE", leftValue, rightValue, leftFormula,
                    rightFormula, status));
        }
        // managed columns keep their own storage keys (for example {@code _Remarks}) in managed_json
        for (String fieldKey : unionManagedKeys(leftManaged, rightManaged)) {
            String leftValue = CaseJsonCodec.textOrNull(leftTexts.managedValue(fieldKey));
            String rightValue = CaseJsonCodec.textOrNull(rightTexts.managedValue(fieldKey));
            String leftFormula = leftFormulas.get(fieldKey);
            String rightFormula = rightFormulas.get(fieldKey);
            boolean same = Objects.equals(leftValue, rightValue) && Objects.equals(leftFormula, rightFormula);
            fields.add(new FieldDiff(fieldKey, fieldKey, "MANAGED", leftValue, rightValue, leftFormula,
                    rightFormula, same ? "SAME" : "DIFFERENT"));
        }

        return new Result(side(tenantId, left, leftSchema), side(tenantId, right, rightSchema), fields,
                summary(fields));
    }

    private TestCaseRecord require(String tenantId, String caseId) {
        return caseRepository.find(tenantId, caseId)
                .orElseThrow(() -> ApiException.notFound("Case " + caseId + " does not exist"));
    }

    private Side side(String tenantId, TestCaseRecord record, ProductSchema schema) {
        String batchName = batchRepository.find(tenantId, record.batchId())
                .map(ImportBatchRow::name)
                .orElse(record.batchId());
        return new Side(record.id(), record.testCaseId(), record.keyValue(), record.batchId(), batchName,
                record.productCode(), schema.name(), record.rowNo(), record.sourceRow());
    }

    /** Union of both trade field lists, left order first, right-only fields appended. */
    private static List<String> unionTradeFields(ProductSchema left, ProductSchema right) {
        Set<String> keys = new LinkedHashSet<>();
        left.tradeFields().forEach(field -> keys.add(field.key()));
        right.tradeFields().forEach(field -> keys.add(field.key()));
        return List.copyOf(keys);
    }

    /** Union of the managed column keys of both rows, left order first. */
    private static List<String> unionManagedKeys(Map<String, Object> left, Map<String, Object> right) {
        Set<String> keys = new LinkedHashSet<>(left.keySet());
        keys.addAll(right.keySet());
        return List.copyOf(keys);
    }

    private String value(ProductSchema schema, String fieldKey, Object raw) {
        return CaseJsonCodec.textOrNull(raw);
    }

    /** Formulas of one case row, trade and managed columns merged. */
    private Map<String, String> formulas(String formulaJson) {
        Map<String, String> formulas = new LinkedHashMap<>(codec.tradeFormulas(formulaJson));
        formulas.putAll(codec.managedFormulas(formulaJson));
        return formulas;
    }

    private static Summary summary(List<FieldDiff> fields) {
        int compared = 0;
        int same = 0;
        int different = 0;
        int leftOnly = 0;
        int rightOnly = 0;
        int formulaDifferent = 0;
        for (FieldDiff field : fields) {
            switch (field.status()) {
                case "LEFT_ONLY" -> leftOnly++;
                case "RIGHT_ONLY" -> rightOnly++;
                default -> {
                    compared++;
                    if ("SAME".equals(field.status())) {
                        same++;
                    } else {
                        different++;
                    }
                }
            }
            if (field.leftFormula() != null && field.rightFormula() != null
                    && !field.leftFormula().equals(field.rightFormula())) {
                formulaDifferent++;
            }
        }
        return new Summary(compared, same, different, leftOnly, rightOnly, formulaDifferent);
    }
}
