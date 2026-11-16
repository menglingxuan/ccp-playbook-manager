package ac.core.service.cases;

import ac.core.common.ApiException;
import java.util.List;
import java.util.Locale;

/**
 * One transient field override applied while an export is rendered.
 *
 * <p>The override follows the same rules as a bulk update but is never written back: it only shapes
 * the values, formulas and CSV cells of a single export run.
 *
 * @param fieldKey  trade field or managed column to override
 * @param mode      {@code VALUE}, {@code FORMULA} or {@code CLEAR}
 * @param value     raw text for {@code VALUE}; parsed with the field type
 * @param formula   formula text for {@code FORMULA}, must start with {@code =}
 * @param valueSet  optional meta set whose item supplies the value or the formula
 * @param valueItem item code inside {@code valueSet}; takes precedence over {@code value}/{@code formula}
 */
public record FieldOverride(
        String fieldKey,
        String mode,
        String value,
        String formula,
        String valueSet,
        String valueItem) {

    /** Resolves the requested mode, defaulting to {@code VALUE}. */
    public BulkFieldUpdateRequest.Mode resolveMode() {
        if (mode == null || mode.isBlank()) {
            return BulkFieldUpdateRequest.Mode.VALUE;
        }
        try {
            return BulkFieldUpdateRequest.Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("Unknown override mode '" + mode + "'");
        }
    }

    /** True when the value or the formula comes from a meta set item. */
    public boolean fromSet() {
        return valueSet != null && !valueSet.isBlank();
    }

    /** Same shape as a bulk update so the resolution rules live in one place. */
    public BulkFieldUpdateRequest toBulkRequest() {
        return new BulkFieldUpdateRequest(fieldKey, mode, value, formula, valueSet, valueItem,
                null, null, List.of(), BulkFieldUpdateRequest.Scope.BATCH.name(), Boolean.FALSE);
    }
}
