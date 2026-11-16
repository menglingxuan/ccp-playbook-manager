package ac.domain.validation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One compiled validation rule: a regular expression or a wildcard pattern matched against the whole
 * value.
 *
 * <p>Both types use full matching, so a REGEX rule {@code \d{3}} accepts only three digits; add
 * {@code .*} yourself when a partial match is wanted.
 *
 * <p>Expressions are limited in length and may not repeat a group that already contains an unbounded
 * quantifier (for example {@code (.*a){14}} or {@code (a+)+}): such patterns backtrack
 * catastrophically and would stall an import of a few thousand rows.
 */
public final class ValidationRule {

    /** Longest accepted rule expression. */
    public static final int MAX_EXPRESSION_LENGTH = 300;

    private final String code;
    private final String label;
    private final ValidationRuleType type;
    private final String expression;
    private final ValidationSeverity severity;
    private final Pattern pattern;
    private volatile Pattern insensitive;

    private ValidationRule(String code, String label, ValidationRuleType type, String expression,
            ValidationSeverity severity, Pattern pattern) {
        this.code = code;
        this.label = label;
        this.type = type;
        this.expression = expression;
        this.severity = severity;
        this.pattern = pattern;
    }

    /**
     * Compiles a rule.
     *
     * @param code       rule code referenced by the field expressions
     * @param label      display label, may be {@code null}
     * @param type       {@code REGEX} or {@code WILDCARD}, {@code null} means {@code WILDCARD}
     * @param expression the pattern text
     * @throws RuleSyntaxException when the pattern is empty or does not compile
     */
    public static ValidationRule compile(String code, String label, String type, String expression) {
        return compile(code, label, type, expression, ValidationSeverity.WARN);
    }

    /**
     * Compiles a rule with a reporting severity.
     *
     * @param severity {@code WARN} only reports, {@code ERROR} blocks the row
     */
    public static ValidationRule compile(String code, String label, String type, String expression,
            ValidationSeverity severity) {
        ValidationRuleType ruleType = ValidationRuleType.of(type);
        String text = expression == null ? "" : expression.trim();
        if (text.isEmpty()) {
            throw new RuleSyntaxException("The rule expression of '" + code + "' is empty");
        }
        if (text.length() > MAX_EXPRESSION_LENGTH) {
            throw new RuleSyntaxException("The rule expression of '" + code + "' is longer than "
                    + MAX_EXPRESSION_LENGTH + " characters");
        }
        String regex = ruleType == ValidationRuleType.REGEX ? text : wildcardToRegex(text);
        rejectNestedQuantifiers(code, regex);
        try {
            return new ValidationRule(code, label, ruleType, text, severity, Pattern.compile(regex));
        } catch (PatternSyntaxException e) {
            throw new RuleSyntaxException("The " + ruleType.name().toLowerCase(java.util.Locale.ROOT)
                    + " expression of '" + code + "' is invalid: " + e.getDescription(), e);
        }
    }

    /**
     * Rejects a quantifier applied to a group that already contains an unbounded quantifier.
     *
     * <p>Such nesting makes the matcher try every split of the input, which turns a 30 character value
     * into seconds of CPU time; the check is syntactic so a rejected rule always carries a clear
     * message instead of a random timeout.
     */
    static void rejectNestedQuantifiers(String code, String regex) {
        Deque<Boolean> saved = new ArrayDeque<>();
        boolean unbounded = false;
        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (c == '\\') {
                if (i + 1 < regex.length() && regex.charAt(i + 1) == 'Q') {
                    int end = regex.indexOf("\\E", i + 2);
                    i = end < 0 ? regex.length() : end + 1;
                } else {
                    i++;
                }
                continue;
            }
            if (c == '[') {
                i = skipCharacterClass(regex, i);
                continue;
            }
            if (c == '(') {
                saved.push(unbounded);
                unbounded = false;
                continue;
            }
            if (c == ')') {
                boolean nested = unbounded;
                unbounded = saved.isEmpty() ? false : saved.pop();
                if (nested && startsQuantifier(regex, i + 1)) {
                    throw new RuleSyntaxException("The rule expression of '" + code
                            + "' repeats a group that already contains *, + or {m,}; rewrite it without"
                            + " nested quantifiers");
                }
                continue;
            }
            if (c == '*' || c == '+' || (c == '{' && isUnboundedCount(regex, i))) {
                unbounded = true;
            }
        }
    }

    /** Index of the closing bracket of a character class, or the last index when it never closes. */
    private static int skipCharacterClass(String regex, int open) {
        for (int i = open + 1; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == ']') {
                return i;
            }
        }
        return regex.length() - 1;
    }

    private static boolean startsQuantifier(String regex, int index) {
        if (index >= regex.length()) {
            return false;
        }
        char c = regex.charAt(index);
        return c == '*' || c == '+' || c == '{';
    }

    /** True for {@code {2,}} but not for the bounded {@code {2}} or {@code {2,5}}. */
    private static boolean isUnboundedCount(String regex, int brace) {
        int close = regex.indexOf('}', brace);
        if (close < 0) {
            return false;
        }
        String body = regex.substring(brace + 1, close);
        int comma = body.indexOf(',');
        return comma >= 0 && body.substring(comma + 1).isBlank();
    }

    /** Converts a wildcard pattern into an equivalent regular expression. */
    static String wildcardToRegex(String wildcard) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < wildcard.length(); i++) {
            char c = wildcard.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                // escape everything a regex engine would otherwise read as syntax
                default -> regex.append("\\Q").append(c).append("\\E");
            }
        }
        return regex.toString();
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public ValidationRuleType type() {
        return type;
    }

    /** How a failure of this rule is reported. */
    public ValidationSeverity severity() {
        return severity;
    }

    public String expression() {
        return expression;
    }

    /** True when the whole value matches this rule. */
    public boolean matches(String value) {
        return matches(value, false);
    }

    /**
     * Matches the whole value.
     *
     * @param value      value text, {@code null} never matches
     * @param ignoreCase compare without regard to case
     */
    public boolean matches(String value, boolean ignoreCase) {
        if (value == null) {
            return false;
        }
        return (ignoreCase ? caseInsensitivePattern() : pattern).matcher(value).matches();
    }

    /** Case-insensitive twin of {@link #pattern}, built on first use. */
    private Pattern caseInsensitivePattern() {
        Pattern cached = insensitive;
        if (cached == null) {
            cached = Pattern.compile(pattern.pattern(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            insensitive = cached;
        }
        return cached;
    }
}
