package ac.domain.validation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Boolean combination of validation rules, for example {@code AMOUNT_POS AND NOT CCY_BLANK}.
 *
 * <p>Grammar (keywords are case insensitive):
 * <pre>
 *   expression := orExpr
 *   orExpr     := andExpr ( OR andExpr )*
 *   andExpr    := unary ( AND unary )*
 *   unary      := NOT unary | primary
 *   primary    := CODE | '(' expression ')'
 * </pre>
 * Evaluation is short-circuiting and left associative.
 */
public final class RuleExpression {

    private static final Set<String> KEYWORDS = Set.of("AND", "OR", "NOT");

    private final Node root;
    private final Set<String> codes;
    private final String text;

    private RuleExpression(Node root, Set<String> codes, String text) {
        this.root = root;
        this.codes = codes;
        this.text = text;
    }

    /**
     * Parses an expression.
     *
     * @param expression rule codes combined with {@code AND}, {@code OR}, {@code NOT} and parentheses
     * @throws RuleSyntaxException when the text is empty, unbalanced or malformed
     */
    public static RuleExpression parse(String expression) {
        String text = expression == null ? "" : expression.trim();
        if (text.isEmpty()) {
            throw new RuleSyntaxException("The rule expression is empty");
        }
        Parser parser = new Parser(tokenize(text));
        Node root = parser.parseExpression();
        parser.requireEnd();
        return new RuleExpression(root, parser.codes, text);
    }

    /** Original expression text. */
    public String text() {
        return text;
    }

    /** Rule codes referenced by this expression, in appearance order. */
    public Set<String> codes() {
        return codes;
    }

    /**
     * Evaluates the expression.
     *
     * @param rulePasses answers whether one rule accepts the value
     */
    public boolean test(Predicate<String> rulePasses) {
        return root.eval(rulePasses);
    }

    private sealed interface Node permits Rule, Not, And, Or {

        boolean eval(Predicate<String> rulePasses);
    }

    private record Rule(String code) implements Node {

        @Override
        public boolean eval(Predicate<String> rulePasses) {
            return rulePasses.test(code);
        }
    }

    private record Not(Node inner) implements Node {

        @Override
        public boolean eval(Predicate<String> rulePasses) {
            return !inner.eval(rulePasses);
        }
    }

    private record And(Node left, Node right) implements Node {

        @Override
        public boolean eval(Predicate<String> rulePasses) {
            return left.eval(rulePasses) && right.eval(rulePasses);
        }
    }

    private record Or(Node left, Node right) implements Node {

        @Override
        public boolean eval(Predicate<String> rulePasses) {
            return left.eval(rulePasses) || right.eval(rulePasses);
        }
    }

    private static List<String> tokenize(String text) {
        java.util.ArrayList<String> tokens = new java.util.ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (Character.isWhitespace(c)) {
                index++;
            } else if (c == '(' || c == ')') {
                tokens.add(String.valueOf(c));
                index++;
            } else if (Character.isLetterOrDigit(c) || c == '_') {
                int start = index;
                while (index < text.length()
                        && (Character.isLetterOrDigit(text.charAt(index)) || text.charAt(index) == '_')) {
                    index++;
                }
                tokens.add(text.substring(start, index));
            } else {
                throw new RuleSyntaxException("Unexpected character '" + c + "' at position " + (index + 1));
            }
        }
        if (tokens.isEmpty()) {
            throw new RuleSyntaxException("The rule expression is empty");
        }
        return List.copyOf(tokens);
    }

    private static final class Parser {

        private final List<String> tokens;
        private final Set<String> codes = new LinkedHashSet<>();
        private int position;

        Parser(List<String> tokens) {
            this.tokens = tokens;
        }

        Node parseExpression() {
            Node left = parseAnd();
            while (matchKeyword("OR")) {
                left = new Or(left, parseAnd());
            }
            return left;
        }

        private Node parseAnd() {
            Node left = parseUnary();
            while (matchKeyword("AND")) {
                left = new And(left, parseUnary());
            }
            return left;
        }

        private Node parseUnary() {
            if (matchKeyword("NOT")) {
                return new Not(parseUnary());
            }
            return parsePrimary();
        }

        private Node parsePrimary() {
            if (position >= tokens.size()) {
                throw new RuleSyntaxException("The rule expression ends unexpectedly");
            }
            String token = tokens.get(position);
            if ("(".equals(token)) {
                position++;
                Node inner = parseExpression();
                require(")");
                return inner;
            }
            if (")".equals(token)) {
                throw new RuleSyntaxException("Unexpected ')' at token " + (position + 1));
            }
            position++;
            String upper = token.toUpperCase(Locale.ROOT);
            if (KEYWORDS.contains(upper)) {
                throw new RuleSyntaxException("'" + upper + "' needs a rule code after it");
            }
            // rule codes are stored in upper case, so the expression may be typed in any case
            codes.add(upper);
            return new Rule(upper);
        }

        void requireEnd() {
            if (position < tokens.size()) {
                throw new RuleSyntaxException("Unexpected '" + tokens.get(position) + "' at token "
                        + (position + 1));
            }
        }

        private void require(String expected) {
            if (position >= tokens.size() || !expected.equals(tokens.get(position))) {
                throw new RuleSyntaxException("Expected '" + expected + "' at token " + (position + 1));
            }
            position++;
        }

        private boolean matchKeyword(String keyword) {
            if (position < tokens.size()
                    && keyword.equals(tokens.get(position).toUpperCase(Locale.ROOT))) {
                position++;
                return true;
            }
            return false;
        }
    }
}
