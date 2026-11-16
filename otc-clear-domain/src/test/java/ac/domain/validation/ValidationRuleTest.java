package ac.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidationRuleTest {

    private static final ValidationRule CCY = ValidationRule.compile("CCY", "Currency", "REGEX", "[A-Z]{3}");
    private static final ValidationRule NOTIONAL = ValidationRule.compile("NOTIONAL", "Amount", "WILDCARD",
            "1*");
    private static final ValidationRule PHONE = ValidationRule.compile("PHONE", "Phone", "WILDCARD",
            "852-????-????");

    @Test
    void regexMatchesTheWholeValue() {
        assertThat(CCY.matches("USD")).isTrue();
        assertThat(CCY.matches("US")).isFalse();
        assertThat(CCY.matches("USDX")).isFalse();
        assertThat(CCY.matches("usd")).isFalse();
    }

    @Test
    void wildcardSupportsStarAndQuestion() {
        assertThat(NOTIONAL.matches("1000000")).isTrue();
        assertThat(NOTIONAL.matches("1")).isTrue();
        assertThat(NOTIONAL.matches("2000")).isFalse();
        assertThat(PHONE.matches("852-1234-5678")).isTrue();
        assertThat(PHONE.matches("852-123-5678")).isFalse();
    }

    @Test
    void wildcardEscapesRegexMetacharacters() {
        ValidationRule dots = ValidationRule.compile("DOTS", null, "WILDCARD", "a.b");
        assertThat(dots.matches("a.b")).isTrue();
        assertThat(dots.matches("axb")).isFalse();

        ValidationRule onlyGlob = ValidationRule.compile("GLOB", null, "WILDCARD", "*");
        assertThat(onlyGlob.matches("anything")).isTrue();
        assertThat(onlyGlob.matches("")).isTrue();
    }

    @Test
    void invalidExpressionsAreRejected() {
        assertThatThrownBy(() -> ValidationRule.compile("BAD", null, "REGEX", "[A-Z"))
                .isInstanceOf(RuleSyntaxException.class)
                .hasMessageContaining("invalid");
        assertThatThrownBy(() -> ValidationRule.compile("EMPTY", null, "WILDCARD", "  "))
                .isInstanceOf(RuleSyntaxException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> ValidationRule.compile("TYPE", null, "GLOB", "a"))
                .isInstanceOf(RuleSyntaxException.class)
                .hasMessageContaining("REGEX or WILDCARD");
    }

    @Test
    void outcomeSkipsBlankValuesAndReportsFailedRules() {
        Map<String, ValidationRule> rules = Map.of(
                "ALPHA", ValidationRule.compile("ALPHA", null, "REGEX", "[A-Z]*"),
                "LEN3", ValidationRule.compile("LEN3", null, "WILDCARD", "???"));
        RuleExpression expression = RuleExpression.parse("alpha and LEN3");

        // asking to skip blanks keeps them out of the result
        assertThat(ValidationOutcome.evaluate(expression, "  ", rules, ValidationOptions.SKIP_BLANK)
                .passed()).isTrue();
        assertThat(ValidationOutcome.evaluate(expression, "", rules, ValidationOptions.SKIP_BLANK)
                .applied()).isTrue();
        // the default run skips blanks too, a missing value is the job of the required flag
        assertThat(ValidationOutcome.evaluate(expression, "", rules).passed()).isTrue();
        // only an explicit run without the skip switch lets these rules reject the blank text
        assertThat(ValidationOutcome.evaluate(expression, "", rules, new ValidationOptions(false, false))
                .passed()).isFalse();
        assertThat(ValidationOutcome.evaluate(expression, "USD", rules).passed()).isTrue();

        ValidationOutcome failed = ValidationOutcome.evaluate(expression, "US1", rules);
        assertThat(failed.passed()).isFalse();
        assertThat(failed.failedRules()).containsExactly("ALPHA");

        assertThat(ValidationOutcome.evaluate(null, "anything", rules).applied()).isFalse();
    }

    @Test
    void ignoreCaseOptionMatchesWithoutRegardToCase() {
        Map<String, ValidationRule> prefixRules =
                Map.of("PREFIX", ValidationRule.compile("PREFIX", null, "WILDCARD", "TD-*"));
        RuleExpression prefix = RuleExpression.parse("PREFIX");
        assertThat(ValidationOutcome.evaluate(prefix, "TD-1", prefixRules).passed()).isTrue();
        assertThat(ValidationOutcome.evaluate(prefix, "td-1", prefixRules).passed()).isFalse();
        assertThat(ValidationOutcome.evaluate(prefix, "td-1", prefixRules, new ValidationOptions(true, false))
                .passed()).isTrue();

        // a character class is affected as well
        Map<String, ValidationRule> isoRules =
                Map.of("ISO", ValidationRule.compile("ISO", null, "REGEX", "[A-Z]{3}"));
        RuleExpression iso = RuleExpression.parse("ISO");
        assertThat(ValidationOutcome.evaluate(iso, "usd", isoRules).passed()).isFalse();
        assertThat(ValidationOutcome.evaluate(iso, "usd", isoRules, new ValidationOptions(true, false))
                .passed()).isTrue();
    }
}
