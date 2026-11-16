package ac.domain.casefile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Texts of one case, split the same way as storage: trade field key to text and managed storage key
 * to text.
 *
 * <p>Business processing (validation, record ids, comparison, export) works on these texts. The
 * "original" texts are the exact cell texts read at import time and every edited value keeps the
 * text the user submitted; only rows imported before the raw text was recorded fall back to the
 * typed value rendered as text.
 */
public record CellTexts(Map<String, String> data, Map<String, String> managed) {

    public static final CellTexts EMPTY = new CellTexts(Map.of(), Map.of());

    public CellTexts {
        data = copy(data);
        managed = copy(managed);
    }

    private static Map<String, String> copy(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>(source.size());
        source.forEach((key, value) -> {
            if (key != null && value != null) {
                result.put(key, value);
            }
        });
        return Map.copyOf(result);
    }

    /**
     * Merges the original texts over the typed values of a stored row.
     *
     * @param data    trade field key to typed value, may be empty
     * @param managed managed storage key to text, may be empty
     * @param raw     original texts, {@code null} for rows stored before they were recorded
     */
    public static CellTexts of(Map<String, Object> data, Map<String, String> managed, CellTexts raw) {
        Map<String, String> trade = new LinkedHashMap<>();
        if (data != null) {
            data.forEach((key, value) -> {
                if (value != null) {
                    trade.put(key, plain(value));
                }
            });
        }
        Map<String, String> columns = new LinkedHashMap<>();
        if (managed != null) {
            columns.putAll(managed);
        }
        if (raw != null) {
            trade.putAll(raw.data());
            columns.putAll(raw.managed());
        }
        return new CellTexts(trade, columns);
    }

    /** Typed value rendered as text: dates ISO, decimals keep their written scale. */
    private static String plain(Object value) {
        return String.valueOf(value);
    }

    /** Text of a trade field, {@code null} when the field carries no value. */
    public String trade(String fieldKey) {
        return data.get(fieldKey);
    }

    /** Text of a managed column, keyed by its storage key such as {@code _Remarks}. */
    public String managedValue(String storageKey) {
        return managed.get(storageKey);
    }

    public boolean hasRaw() {
        return !data.isEmpty() || !managed.isEmpty();
    }
}
