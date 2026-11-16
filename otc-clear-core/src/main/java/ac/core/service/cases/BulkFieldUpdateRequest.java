package ac.core.service.cases;

import ac.core.common.ApiException;
import java.util.List;
import java.util.Locale;

/**
 * Bulk field update of every case in a batch.
 *
 * @param fieldKey  trade field or managed column to change
 * @param mode      {@code VALUE}, {@code FORMULA} or {@code CLEAR}
 * @param value     raw text for {@code VALUE}; parsed with the field type
 * @param formula   formula text for {@code FORMULA}, must start with {@code =}
 * @param valueSet  optional meta set whose item supplies the value or the formula
 * @param valueItem item code inside {@code valueSet}; takes precedence over {@code value}/{@code formula}
 * @param query     optional free text filter matching the case search text
 * @param hasFormula only cases that already contain a formula when true
 * @param caseIds   optional explicit selection; empty means the filter or the whole batch
 * @param scope     optional explicit scope, {@code BATCH}, {@code FILTER} or {@code SELECTION}; required
 *                  when neither a selection nor a filter is sent, so a mistyped payload can never
 *                  rewrite the whole batch by accident
 * @param dryRun    when true nothing is written, only the impact is reported
 */
public record BulkFieldUpdateRequest(
        String fieldKey,
        String mode,
        String value,
        String formula,
        String valueSet,
        String valueItem,
        String query,
        Boolean hasFormula,
        List<String> caseIds,
        String scope,
        Boolean dryRun) {

    /** How the target field is rewritten. */
    public enum Mode {
        VALUE,
        FORMULA,
        CLEAR
    }

    /** Which cases the update touches. */
    public enum Scope {
        BATCH,
        FILTER,
        SELECTION
    }

    public BulkFieldUpdateRequest {
        caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
    }

    /** Resolves the requested mode, defaulting to {@code VALUE}. */
    public Mode resolveMode() {
        if (mode == null || mode.isBlank()) {
            return Mode.VALUE;
        }
        try {
            return Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("Unknown update mode '" + mode + "'");
        }
    }

    /**
     * Resolves the scope and rejects a payload that would silently rewrite the whole batch.
     *
     * <p>An empty selection plus no filter is only accepted when the caller states
     * {@code scope=BATCH}; a misspelled property therefore fails with 400 instead of touching every
     * case in the batch.
     */
    public Scope resolveScope() {
        if (!caseIds.isEmpty()) {
            return Scope.SELECTION;
        }
        boolean filtered = (query != null && !query.isBlank()) || hasFormula != null;
        if (filtered) {
            return Scope.FILTER;
        }
        if (scope == null || scope.isBlank()) {
            throw ApiException.invalid("Select cases or a filter, or send scope=BATCH to update the whole"
                    + " batch");
        }
        try {
            Scope declared = Scope.valueOf(scope.trim().toUpperCase(Locale.ROOT));
            if (declared != Scope.BATCH) {
                throw ApiException.invalid("Scope " + declared.name()
                        + " needs a selection or a filter in the payload");
            }
            return Scope.BATCH;
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("Unknown update scope '" + scope + "'");
        }
    }

    public boolean dryRunEnabled() {
        return Boolean.TRUE.equals(dryRun);
    }

    public boolean selectionOnly() {
        return resolveScope() == Scope.SELECTION;
    }

    /** True when the value or the formula comes from a meta set item. */
    public boolean fromSet() {
        return valueSet != null && !valueSet.isBlank();
    }
}
