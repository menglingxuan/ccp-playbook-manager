package ac.core.service.validation;

import ac.core.common.ApiException;
import ac.core.persistence.ImportBatchRepository;
import ac.core.persistence.ImportBatchRow;
import ac.core.persistence.TestCaseRecord;
import ac.core.persistence.TestCaseRepository;
import ac.core.service.product.ProductCatalog;
import ac.core.service.cases.CaseJsonCodec;
import ac.domain.casefile.CellTexts;
import ac.domain.product.ProductSchema;
import ac.domain.validation.ValidationOptions;
import ac.domain.validation.ValidationSeverity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Read-only rule validation of one case or one batch; nothing is stored and nothing is blocked.
 *
 * <p>The screens highlight the failing rows and list the fields that carry no rule at all, so a green
 * result is never mistaken for "fully covered".
 */
@Service
public class ValidationService {

    /** Upper bound of rows checked in one batch request, keeps the response small. */
    static final int MAX_CASES = 2000;

    /**
     * One checked row of a batch.
     *
     * @param caseId     case id
     * @param rowNo      position inside the batch
     * @param testCaseId test case id
     * @param failures   failing fields, empty when the row passed
     * @param blocked    true when a blocking rule failed, so the row would be rejected by an import
     */
    public record RowResult(String caseId, int rowNo, String testCaseId, List<FieldRules.Failure> failures,
            boolean blocked) {
        public RowResult {
            failures = List.copyOf(failures);
        }
    }

    /**
     * One field of the batch report.
     *
     * @param fieldKey   internal field key
     * @param header     display header
     * @param scope      {@code TRADE} or {@code MANAGED}
     * @param ruled      false when the field carries no rule
     * @param implicit   true when the coverage comes from the value type referencing a meta set
     * @param expression the field expression, {@code null} when unruled
     * @param checked    rows that actually carried a value to check
     * @param failed     rows that failed
     * @param blocked    rows that failed a blocking rule
     */
    public record FieldStat(String fieldKey, String header, String scope, boolean ruled, boolean implicit,
            String expression, int checked, int failed, int blocked) {
    }

    /**
     * Validation result of one case or one batch.
     *
     * @param scope         {@code CASE} or {@code BATCH}
     * @param targetId      case id or batch id
     * @param productCode   product of the checked rows
     * @param ignoreCase    true when rules were matched case-insensitively
     * @param skipBlank     true when blank values were left unchecked
     * @param rowsChecked   rows inspected
     * @param rowsFailed    rows with at least one failing field
     * @param rowsBlocked   rows with at least one failure of severity {@code ERROR}
     * @param failures      every failing field of every row
     * @param fields        per field counters, ruled and unruled alike
     * @param unruledFields headers of the fields without any rule
     * @param truncated     true when only the first {@link #MAX_CASES} rows were checked
     */
    public record Result(String scope, String targetId, String productCode, boolean ignoreCase,
            boolean skipBlank, int rowsChecked, int rowsFailed, int rowsBlocked, List<RowResult> failures,
            List<FieldStat> fields, List<String> unruledFields, boolean truncated) {
        public Result {
            failures = List.copyOf(failures);
            fields = List.copyOf(fields);
            unruledFields = List.copyOf(unruledFields);
        }
    }

    private final TestCaseRepository cases;
    private final ImportBatchRepository batches;
    private final ProductCatalog products;
    private final FieldRules fieldRules;
    private final CaseJsonCodec codec;

    public ValidationService(TestCaseRepository cases, ImportBatchRepository batches, ProductCatalog products,
            FieldRules fieldRules, CaseJsonCodec codec) {
        this.cases = cases;
        this.batches = batches;
        this.products = products;
        this.fieldRules = fieldRules;
        this.codec = codec;
    }

    /** Validates one case; unknown cases answer 404. */
    public Result validateCase(String tenantId, String caseId, ValidationOptions options) {
        TestCaseRecord record = cases.find(tenantId, caseId)
                .orElseThrow(() -> ApiException.notFound("Case " + caseId + " does not exist"));
        return validate(tenantId, "CASE", caseId, record.productCode(), List.of(record), false, options);
    }

    /** Validates every active case of one batch; unknown batches answer 404. */
    public Result validateBatch(String tenantId, String batchId, ValidationOptions options) {
        ImportBatchRow batch = batches.find(tenantId, batchId)
                .filter(row -> !"DELETED".equals(row.status()))
                .orElseThrow(() -> ApiException.notFound("Batch " + batchId + " does not exist"));
        List<TestCaseRecord> rows = cases.listForExport(tenantId, batchId);
        boolean truncated = rows.size() > MAX_CASES;
        if (truncated) {
            rows = rows.subList(0, MAX_CASES);
        }
        return validate(tenantId, "BATCH", batchId, batch.productCode(), rows, truncated, options);
    }

    private Result validate(String tenantId, String scope, String targetId, String productCode,
            List<TestCaseRecord> rows, boolean truncated, ValidationOptions options) {
        ValidationOptions effective = options == null ? ValidationOptions.DEFAULT : options;
        ProductSchema schema = products.require(tenantId, productCode);
        FieldRules.Plan plan = fieldRules.plan(tenantId, productCode, schema);

        Map<String, int[]> counters = new LinkedHashMap<>();
        plan.bindings().forEach(binding -> counters.put(binding.fieldKey(), new int[3]));
        List<RowResult> failures = new ArrayList<>();
        int failed = 0;
        int blocked = 0;
        for (TestCaseRecord row : rows) {
            List<FieldRules.Check> checks = fieldRules.check(plan, schema, codec.texts(row), effective);
            for (FieldRules.Check check : checks) {
                int[] counter = counters.get(check.fieldKey());
                if (counter != null && checked(effective, check)) {
                    counter[0]++;
                }
            }
            List<FieldRules.Failure> rowFailures = fieldRules.failures(checks);
            boolean rowBlocked = false;
            for (FieldRules.Failure failure : rowFailures) {
                int[] counter = counters.get(failure.fieldKey());
                if (counter != null) {
                    counter[1]++;
                    if (failure.severity() == ValidationSeverity.ERROR) {
                        counter[2]++;
                    }
                }
                rowBlocked |= failure.severity() == ValidationSeverity.ERROR;
            }
            if (!rowFailures.isEmpty()) {
                failed++;
                if (rowBlocked) {
                    blocked++;
                }
                failures.add(new RowResult(row.id(), row.rowNo(), row.testCaseId(), rowFailures, rowBlocked));
            }
        }

        List<FieldStat> stats = new ArrayList<>();
        for (FieldRules.Check check : fieldRules.check(plan, schema, CellTexts.EMPTY, effective)) {
            int[] counter = counters.getOrDefault(check.fieldKey(), new int[3]);
            stats.add(new FieldStat(check.fieldKey(), check.header(), check.scope(), check.applied(),
                    check.implicit(), check.expression(), counter[0], counter[1], counter[2]));
        }
        List<String> unruled = stats.stream().filter(stat -> !stat.ruled()).map(FieldStat::header).toList();
        return new Result(scope, targetId, productCode, effective.ignoreCase(), effective.skipBlank(),
                rows.size(), failed, blocked, failures, stats, unruled, truncated);
    }

    /** True when this check carried a value the run actually inspected. */
    private static boolean checked(ValidationOptions options, FieldRules.Check check) {
        if (!check.applied()) {
            return false;
        }
        return !options.skipBlank() || (check.value() != null && !check.value().isBlank());
    }
}
