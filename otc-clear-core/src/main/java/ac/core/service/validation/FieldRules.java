package ac.core.service.validation;

import ac.core.persistence.ProductRepository;
import ac.core.service.meta.MetaService;
import ac.domain.casefile.CellTexts;
import ac.domain.layout.ManagedColumnClassifier;
import ac.domain.product.FieldDef;
import ac.domain.product.FieldRole;
import ac.domain.product.ProductSchema;
import ac.domain.validation.RuleExpression;
import ac.domain.validation.RuleSyntaxException;
import ac.domain.validation.ValidationOptions;
import ac.domain.validation.ValidationOutcome;
import ac.domain.validation.ValidationRule;
import ac.domain.validation.ValidationSeverity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Evaluates the validation rules bound to the fields of a product.
 *
 * <p>Rules are defined in {@code VALIDATION} meta sets, bound to a field as a boolean expression over
 * rule codes and evaluated against the stored value text; blank values are never a rule failure. The
 * binding lives on the product field rows, so a derived product may override what it inherited.
 */
@Service
public class FieldRules {

    private final ProductRepository products;
    private final MetaService metaService;

    public FieldRules(ProductRepository products, MetaService metaService) {
        this.products = products;
        this.metaService = metaService;
    }

    /**
     * One field that carries a rule binding.
     *
     * @param fieldKey  internal field key
     * @param header    display header
     * @param expression parsed expression, {@code null} for an implicit collection rule
     * @param ruleSet   code of the referenced meta set when the value type declares one
     */
    public record Binding(String fieldKey, String header, RuleExpression expression, String ruleSet) {

        /** True when the coverage comes from the value type referencing a meta set. */
        public boolean implicit() {
            return expression == null && ruleSet != null;
        }

        /** Expression text shown by the panels. */
        public String expressionText() {
            return expression != null ? expression.text() : "SET:" + ruleSet;
        }
    }

    /**
     * Rule bindings of one product, in field order.
     *
     * @param bindings fields that declare a rule
     * @param rules    every enabled rule of the tenant, by code
     * @param unruled  field keys without any rule, in field order
     */
    public record Plan(List<Binding> bindings, Map<String, ValidationRule> rules, List<String> unruled) {
        public Plan {
            bindings = List.copyOf(bindings);
            unruled = List.copyOf(unruled);
        }

        public boolean empty() {
            return bindings.isEmpty();
        }
    }

    /**
     * Loads the plan of one product.
     *
     * <p>An unknown product or a broken expression is skipped instead of failing the request: the
     * validation screens must keep working after a definition change. A field whose value type
     * references a meta set counts as covered even without an explicit expression, because the type
     * layer already validates it against that set.
     */
    public Plan plan(String tenantId, String productCode, ProductSchema schema) {
        Map<String, ValidationRule> rules = metaService.rules(tenantId);
        Map<String, String> expressions = new LinkedHashMap<>();
        products.findByCode(tenantId, productCode).ifPresent(product -> products
                .listFields(tenantId, product.id()).stream()
                .filter(field -> field.validationExpr() != null && !field.validationExpr().isBlank())
                .forEach(field -> expressions.put(field.fieldKey(), field.validationExpr())));
        Map<String, String> ruleSets = referencedSets(tenantId, productCode);

        List<Binding> bindings = new ArrayList<>();
        List<String> unruled = new ArrayList<>();
        for (FieldDef field : schema.fields()) {
            String expression = expressions.get(field.key());
            if (expression == null) {
                String ruleSet = ruleSets.get(field.key());
                if (ruleSet != null) {
                    // the value type validates the value against that set, so the field is covered
                    bindings.add(new Binding(field.key(), field.header(), null, ruleSet));
                } else {
                    unruled.add(field.key());
                }
                continue;
            }
            try {
                RuleExpression parsed = RuleExpression.parse(expression);
                if (!planRulesCover(parsed, rules)) {
                    // a deleted rule must never turn every value into a failure
                    unruled.add(field.key());
                    continue;
                }
                bindings.add(new Binding(field.key(), field.header(), parsed, null));
            } catch (RuleSyntaxException e) {
                unruled.add(field.key());
            }
        }
        return new Plan(bindings, rules, unruled);
    }

    /** Field key to the meta set its value type references, for the fields that declare one. */
    private Map<String, String> referencedSets(String tenantId, String productCode) {
        Map<String, String> sets = new LinkedHashMap<>();
        products.findByCode(tenantId, productCode).ifPresent(product -> {
            Map<String, String> byValueType = metaService.valueTypeSets(tenantId);
            for (ProductRepository.ProductFieldRow field : products.listFields(tenantId, product.id())) {
                String set = byValueType.get(field.valueTypeCode());
                if (set != null) {
                    sets.put(field.fieldKey(), set);
                }
            }
        });
        return sets;
    }

    /** True when every rule code of the expression still exists. */
    private boolean planRulesCover(RuleExpression expression, Map<String, ValidationRule> rules) {
        for (String code : expression.codes()) {
            if (!rules.containsKey(code)) {
                return false;
            }
        }
        return true;
    }

    /**
     * One failed check.
     *
     * @param fieldKey    internal field key
     * @param header      display header
     * @param value       checked text
     * @param expression  the field expression
     * @param failedRules codes of the rules that rejected the value
     * @param severity    strongest severity of the failed rules
     */
    public record Failure(String fieldKey, String header, String value, String expression,
            List<String> failedRules, ValidationSeverity severity) {
        public Failure {
            failedRules = List.copyOf(failedRules);
            severity = severity == null ? ValidationSeverity.WARN : severity;
        }
    }

    /**
     * One checked field, kept even when it passes so the panel can show what was verified.
     *
     * @param applied  false when the field declares no rule
     * @param implicit true when the coverage comes from the value type referencing a meta set
     * @param failures codes of the rules that rejected the value, empty when it passed
     * @param severity strongest severity of the failed rules
     */
    public record Check(String fieldKey, String header, String scope, String value, boolean applied,
            String expression, boolean implicit, List<String> failedRules, ValidationSeverity severity) {
        public Check {
            failedRules = List.copyOf(failedRules);
            severity = severity == null ? ValidationSeverity.WARN : severity;
        }

        public boolean passed() {
            return failedRules.isEmpty();
        }
    }

    /** Checks one row with the default options (case-sensitive, blank values skipped). */
    public List<Check> check(Plan plan, ProductSchema schema, CellTexts texts) {
        return check(plan, schema, texts, ValidationOptions.DEFAULT);
    }

    /**
     * Checks one row against the original texts of its cells.
     *
     * <p>A binding without an expression comes from the value type referencing a meta set; the type
     * layer already validates that value, so it only counts as coverage here.
     *
     * @param texts   original texts; trade fields are keyed by field key, managed columns by storage key
     * @param options case sensitivity and blank handling of this run
     */
    public List<Check> check(Plan plan, ProductSchema schema, CellTexts texts, ValidationOptions options) {
        Map<String, Check> byKey = new LinkedHashMap<>();
        // managed values live under their storage key (_Remarks), not under the field key
        Map<String, String> managedKeys = ManagedColumnClassifier.storageKeys(schema.fields());
        for (Binding binding : plan.bindings()) {
            FieldDef field = schema.byKey().get(binding.fieldKey());
            boolean managedField = field != null && field.role() == FieldRole.MANAGED;
            String value = managedField
                    ? texts.managedValue(managedKeys.getOrDefault(binding.fieldKey(), binding.fieldKey()))
                    : texts.trade(binding.fieldKey());
            if (binding.implicit()) {
                byKey.put(binding.fieldKey(), new Check(binding.fieldKey(), binding.header(),
                        managedField ? "MANAGED" : "TRADE", value, true,
                        binding.expressionText(), true, List.of(), ValidationSeverity.WARN));
                continue;
            }
            ValidationOutcome outcome =
                    ValidationOutcome.evaluate(binding.expression(), value, plan.rules(), options);
            byKey.put(binding.fieldKey(), new Check(binding.fieldKey(), binding.header(),
                    managedField ? "MANAGED" : "TRADE", value, outcome.applied(), outcome.expression(),
                    false, outcome.failedRules(), outcome.severity()));
        }
        for (String unruled : plan.unruled()) {
            FieldDef field = schema.byKey().get(unruled);
            boolean managedField = field != null && field.role() == FieldRole.MANAGED;
            String value = managedField
                    ? texts.managedValue(managedKeys.getOrDefault(unruled, unruled))
                    : texts.trade(unruled);
            byKey.put(unruled, new Check(unruled, field == null ? unruled : field.header(),
                    managedField ? "MANAGED" : "TRADE", value, false, null, false, List.of(),
                    ValidationSeverity.WARN));
        }
        return List.copyOf(byKey.values());
    }

    /** Failures inside one row, empty when every ruled field passed. */
    public List<Failure> failures(List<Check> checks) {
        List<Failure> failures = new ArrayList<>();
        for (Check check : checks) {
            if (!check.passed()) {
                failures.add(new Failure(check.fieldKey(), check.header(), check.value(),
                        check.expression(), check.failedRules(), check.severity()));
            }
        }
        return failures;
    }

    /**
     * Checks many rows at once, used by the import preview and the import commit.
     *
     * @param textsByRow source row number to the original texts of that row
     * @param options    case sensitivity and blank handling of this run
     */
    public List<RowValidation> checkRows(Plan plan, ProductSchema schema,
            Map<Integer, CellTexts> textsByRow, ValidationOptions options) {
        List<RowValidation> results = new ArrayList<>();
        if (plan.empty()) {
            return results;
        }
        for (Map.Entry<Integer, CellTexts> entry : textsByRow.entrySet()) {
            List<Check> checks = check(plan, schema, entry.getValue(), options);
            List<Failure> failures = failures(checks);
            if (!failures.isEmpty()) {
                results.add(new RowValidation(entry.getKey(), failures));
            }
        }
        return results;
    }

    /**
     * Typed value as text: dates render as ISO, numbers keep their written scale.
     *
     * <p>Only used for rows stored before the original cell text was recorded; new rows always carry
     * their original text.
     */
    public static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? null : text;
    }
}
