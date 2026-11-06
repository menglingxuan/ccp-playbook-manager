package ac.domain.product;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Converts between raw cell text and typed field values.
 *
 * <p>Parsing returns {@code null} for blank input and throws {@link FieldFormatException} for
 * unparsable input; callers decide whether that is a warning or an error.
 */
public final class FieldCodec {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/M/d", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy.M.d", Locale.ROOT),
            DateTimeFormatter.ofPattern("d/M/yyyy", Locale.ROOT),
            DateTimeFormatter.ofPattern("d-M-yyyy", Locale.ROOT),
            DateTimeFormatter.ofPattern("M/d/yyyy", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT),
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ROOT),
            DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ROOT));

    private FieldCodec() {
    }

    public static Object parse(FieldDef field, String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        return switch (field.type()) {
            case TEXT, ENUM -> text;
            case INTEGER -> parseInteger(field, text);
            case DECIMAL -> parseDecimal(field, text);
            case DATE -> parseDate(field, text);
            case BOOLEAN -> parseBoolean(field, text);
        };
    }

    public static String format(FieldDef field, Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).toPlainString();
        }
        return String.valueOf(value);
    }

    /**
     * Canonical representation used inside record id hashes: identical logical values must produce
     * identical text regardless of formatting differences in the source file.
     */
    public static String toCanonical(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.signum() == 0 ? "0" : decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Number number) {
            return toCanonical(new BigDecimal(number.toString()));
        }
        return String.valueOf(value);
    }

    /**
     * Canonical representation aware of the field type, so a JSON round-tripped value hashes the
     * same way as a freshly parsed cell.
     */
    public static String canonical(FieldDef field, Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            try {
                return toCanonical(parse(field, text));
            } catch (RuntimeException e) {
                return text.trim();
            }
        }
        return toCanonical(value);
    }

    private static Object parseInteger(FieldDef field, String text) {
        String normalized = normalizeNumber(field, text);
        try {
            BigDecimal decimal = new BigDecimal(normalized);
            if (decimal.stripTrailingZeros().scale() > 0) {
                throw new FieldFormatException(field.key(), text, "Value '" + text + "' is not an integer");
            }
            return decimal.longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new FieldFormatException(field.key(), text, "Value '" + text + "' is not an integer");
        }
    }

    private static Object parseDecimal(FieldDef field, String text) {
        String normalized = normalizeNumber(field, text);
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            throw new FieldFormatException(field.key(), text, "Value '" + text + "' is not a number");
        }
    }

    private static Object parseDate(FieldDef field, String text) {
        String value = text;
        if (value.length() > 10 && value.contains("T")) {
            value = value.substring(0, 10);
        }
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
                // try the next supported pattern
            }
        }
        throw new FieldFormatException(field.key(), text, "Value '" + text + "' is not a supported date");
    }

    private static Object parseBoolean(FieldDef field, String text) {
        String value = text.toLowerCase(Locale.ROOT).replace(" ", "");
        return switch (value) {
            case "y", "yes", "true", "t", "1" -> Boolean.TRUE;
            case "n", "no", "false", "f", "0" -> Boolean.FALSE;
            default -> throw new FieldFormatException(field.key(), text, "Value '" + text + "' is not a boolean");
        };
    }

    private static String normalizeNumber(FieldDef field, String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c >= '\uFF10' && c <= '\uFF19') {
                sb.append((char) (c - 0xFEE0));
            } else if (c == ',' || c == ' ' || c == '\'' || c == '\u00A0') {
                continue;
            } else {
                sb.append(c);
            }
        }
        String normalized = sb.toString();
        if (normalized.isEmpty()) {
            throw new FieldFormatException(field.key(), text, "Value '" + text + "' is not a number");
        }
        return normalized;
    }
}
