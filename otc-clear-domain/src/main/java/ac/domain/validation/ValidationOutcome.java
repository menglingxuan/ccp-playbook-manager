package ac.domain.validation;

import java.util.List;
import java.util.Map;

/**
 * Outcome of checking one value against one rule expression.
 *
 * @param applied     false when the field declares no rule, so nothing was checked
 * @param passed      true when the value satisfies the expression
 * @param expression  the checked expression, {@code null} when {@code applied} is false
 * @param failedRules codes of the leaf rules that rejected the value, in expression order
 * @param severity    severity of this failure, {@link ValidationSeverity#WARN} when it passed
 */
public record ValidationOutcome(boolean applied, boolean passed, String expression,
        List<String> failedRules, ValidationSeverity severity) {

    public ValidationOutcome {
        failedRules = failedRules == null ? List.of() : List.copyOf(failedRules);
        severity = severity == null ? ValidationSeverity.WARN : severity;
    }

    /** The value was never checked because the field has no rule. */
    public static ValidationOutcome notApplied() {
        return new ValidationOutcome(false, true, null, List.of(), ValidationSeverity.WARN);
    }

    /**
     * Checks one value with the default options: case-sensitive, blank values skipped.
     *
     * @param expression parsed expression, {@code null} when the field has no rule
     * @param text       the value text
     * @param rules      compiled rules by code
     */
    public static ValidationOutcome evaluate(RuleExpression expression, String text,
            Map<String, ValidationRule> rules) {
        return evaluate(expression, text, rules, ValidationOptions.DEFAULT);
    }

    /**
     * Checks one value.
     *
     * <p>A failure takes the strongest severity of the rules that rejected the value, so one ERROR
     * rule inside an {@code AND} chain blocks the row even when another rule only warns.
     *
     * @param expression parsed expression, {@code null} when the field has no rule
     * @param text       the value text, {@code null} means an empty value
     * @param rules      compiled rules by code
     * @param options    case sensitivity and blank handling of this run
     */
    public static ValidationOutcome evaluate(RuleExpression expression, String text,
            Map<String, ValidationRule> rules, ValidationOptions options) {
        ValidationOptions effective = options == null ? ValidationOptions.DEFAULT : options;
        if (expression == null) {
            return notApplied();
        }
        if (effective.skipBlank() && (text == null || text.isBlank())) {
            // a blank value stays the business of the required flag, never a rule failure
            return new ValidationOutcome(true, true, expression.text(), List.of(), ValidationSeverity.WARN);
        }
        String value = text == null ? "" : text;
        List<String> failed = new java.util.ArrayList<>();
        boolean passed = expression.test(code -> {
            ValidationRule rule = rules.get(code);
            boolean ok = rule != null && rule.matches(value, effective.ignoreCase());
            if (!ok) {
                failed.add(code);
            }
            return ok;
        });
        if (passed) {
            return new ValidationOutcome(true, true, expression.text(), List.of(), ValidationSeverity.WARN);
        }
        ValidationSeverity severity = failed.stream()
                .map(rules::get)
                .filter(rule -> rule != null && rule.severity() == ValidationSeverity.ERROR)
                .findFirst()
                .map(rule -> ValidationSeverity.ERROR)
                .orElse(ValidationSeverity.WARN);
        return new ValidationOutcome(true, false, expression.text(), failed, severity);
    }
}
