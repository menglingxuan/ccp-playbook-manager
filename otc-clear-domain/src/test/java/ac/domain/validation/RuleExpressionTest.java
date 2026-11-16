package ac.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class RuleExpressionTest {

    /** Rule A accepts "a", rule B accepts "b", rule C accepts "c". */
    private static boolean passes(String value, String code) {
        return value.equalsIgnoreCase(code.toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean test(String expression, String value) {
        return RuleExpression.parse(expression).test(code -> passes(value, code));
    }

    @Test
    void singleRuleAndConjunction() {
        assertThat(test("A", "a")).isTrue();
        assertThat(test("A", "b")).isFalse();
        assertThat(test("A AND B", "ab")).isFalse();
        assertThat(RuleExpression.parse("A AND B").test(code -> true)).isTrue();
        assertThat(RuleExpression.parse("A AND B").test(code -> false)).isFalse();
    }

    @Test
    void disjunctionAndNegation() {
        assertThat(RuleExpression.parse("A OR B").test(code -> "B".equals(code))).isTrue();
        assertThat(RuleExpression.parse("NOT A").test(code -> false)).isTrue();
        assertThat(RuleExpression.parse("NOT A").test(code -> true)).isFalse();
        assertThat(RuleExpression.parse("a and not b").test(code -> "A".equals(code))).isTrue();
    }

    @Test
    void parenthesesChangePrecedence() {
        // A OR (B AND C): only B is true
        assertThat(RuleExpression.parse("A OR B AND C").test(code -> "B".equals(code))).isFalse();
        assertThat(RuleExpression.parse("(A OR B) AND C").test(code -> "B".equals(code))).isFalse();
        assertThat(RuleExpression.parse("(A OR B) AND (C OR B)").test(code -> "B".equals(code))).isTrue();
        assertThat(RuleExpression.parse("A OR (B OR C)").test(code -> "C".equals(code))).isTrue();
    }

    @Test
    void collectsTheReferencedCodes() {
        Set<String> codes = RuleExpression.parse("A AND (B OR NOT C) AND A").codes();
        assertThat(codes).containsExactly("A", "B", "C");
    }

    @Test
    void malformedExpressionsAreRejected() {
        assertThatThrownBy(() -> RuleExpression.parse(""))
                .isInstanceOf(RuleSyntaxException.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> RuleExpression.parse("A AND"))
                .isInstanceOf(RuleSyntaxException.class).hasMessageContaining("ends unexpectedly");
        assertThatThrownBy(() -> RuleExpression.parse("(A AND B"))
                .isInstanceOf(RuleSyntaxException.class).hasMessageContaining("Expected ')'");
        assertThatThrownBy(() -> RuleExpression.parse("A B"))
                .isInstanceOf(RuleSyntaxException.class).hasMessageContaining("Unexpected 'B'");
        assertThatThrownBy(() -> RuleExpression.parse("A AND OR B"))
                .isInstanceOf(RuleSyntaxException.class);
        assertThatThrownBy(() -> RuleExpression.parse("A & B"))
                .isInstanceOf(RuleSyntaxException.class).hasMessageContaining("Unexpected character");
        assertThatThrownBy(() -> RuleExpression.parse("AND A"))
                .isInstanceOf(RuleSyntaxException.class).hasMessageContaining("needs a rule code");
    }
}
