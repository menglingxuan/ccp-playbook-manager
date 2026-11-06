package ac.domain.clone;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders clone naming templates.
 *
 * <p>Supported tokens: {@code {base}}, {@code {seq}} / {@code {seq:02}}, {@code {uuid}},
 * {@code {date:yyyyMMdd}}, {@code {batch}}.
 */
public final class NamingTemplate {

    private static final Pattern TOKEN = Pattern.compile("\\{([a-zA-Z]+)(?::([^}]+))?}");

    private NamingTemplate() {
    }

    /**
     * Values available to a template.
     *
     * @param base     value of the source field
     * @param sequence 1-based copy sequence
     * @param uuid     pre generated uuid, shared by all copies of one clone run
     * @param date     clone date
     * @param batch    target batch short id
     */
    public record Context(String base, int sequence, String uuid, LocalDate date, String batch) {
    }

    public static String render(String template, Context context) {
        if (template == null || template.isBlank()) {
            return context.base();
        }
        Matcher matcher = TOKEN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
            String argument = matcher.group(2);
            String replacement = switch (token) {
                case "base" -> context.base() == null ? "" : context.base();
                case "seq" -> renderSequence(context.sequence(), argument);
                case "uuid" -> context.uuid() == null ? "" : context.uuid();
                case "date" -> renderDate(context.date(), argument);
                case "batch" -> context.batch() == null ? "" : context.batch();
                default -> throw new IllegalArgumentException("Unsupported template token '{" + token + "}'");
            };
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** Checks the template syntax without rendering it. */
    public static void validate(String template) {
        if (template == null || template.isBlank()) {
            return;
        }
        Matcher matcher = TOKEN.matcher(template);
        while (matcher.find()) {
            String token = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
            if (!switch (token) {
                case "base", "seq", "uuid", "date", "batch" -> true;
                default -> false;
            }) {
                throw new IllegalArgumentException("Unsupported template token '{" + token + "}'");
            }
        }
    }

    private static String renderSequence(int sequence, String argument) {
        if (argument == null || argument.isBlank()) {
            return Integer.toString(sequence);
        }
        try {
            int width = Integer.parseInt(argument.trim());
            return String.format("%0" + width + "d", sequence);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid sequence width '" + argument + "'");
        }
    }

    private static String renderDate(LocalDate date, String argument) {
        LocalDate value = date == null ? LocalDate.now() : date;
        String pattern = argument == null || argument.isBlank() ? "yyyyMMdd" : argument.trim();
        return value.format(DateTimeFormatter.ofPattern(pattern));
    }
}
