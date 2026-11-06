package ac.domain.casefile;

import ac.domain.product.FieldCodec;
import ac.domain.product.FieldDef;
import ac.domain.product.FieldFormatException;
import ac.domain.product.FieldType;
import ac.domain.product.ProductSchema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validates edited values against the product schema and returns normalized values. */
public final class CaseValidator {

    private CaseValidator() {
    }

    /**
     * Result of a validation run.
     *
     * @param values normalized typed values for the fields that passed parsing
     * @param issues every problem found, not just the first one
     */
    public record CaseValidation(Map<String, Object> values, List<ValidationIssue> issues) {

        public CaseValidation {
            values = Map.copyOf(values);
            issues = List.copyOf(issues);
        }

        public boolean valid() {
            return issues.stream().noneMatch(issue -> issue.level() == IssueLevel.ERROR);
        }
    }

    /**
     * Validates raw text values. Fields absent from {@code rawValues} are treated as untouched and
     * are not reported as missing.
     */
    public static CaseValidation validate(ProductSchema schema, Map<String, String> rawValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<ValidationIssue> issues = new ArrayList<>();
        for (Map.Entry<String, String> entry : rawValues.entrySet()) {
            FieldDef field = schema.byKey().get(entry.getKey());
            if (field == null) {
                issues.add(ValidationIssue.error("UNKNOWN_FIELD", "Unknown field '" + entry.getKey() + "'")
                        .forField(entry.getKey()));
                continue;
            }
            try {
                Object value = FieldCodec.parse(field, entry.getValue());
                if (value != null) {
                    values.put(field.key(), value);
                }
            } catch (FieldFormatException e) {
                issues.add(ValidationIssue.error("TYPE_MISMATCH", e.getMessage()).forField(field.key()));
            }
        }
        for (FieldDef required : schema.requiredFields()) {
            if (!rawValues.containsKey(required.key())) {
                continue;
            }
            Object value = values.get(required.key());
            if (value == null || String.valueOf(value).isBlank()) {
                issues.add(ValidationIssue.error("MISSING_REQUIRED_FIELD",
                        "Required field '" + required.header() + "' is empty").forField(required.key()));
            }
        }
        return new CaseValidation(values, issues);
    }

    /** Checks that a typed value is compatible with the field type. */
    public static boolean isCompatible(FieldDef field, Object value) {
        if (value == null) {
            return true;
        }
        FieldType type = field.type();
        return switch (type) {
            case TEXT, ENUM -> value instanceof String;
            case DECIMAL -> value instanceof BigDecimal || value instanceof Number;
            case INTEGER -> value instanceof Long || value instanceof Integer;
            case DATE -> value instanceof LocalDate || value instanceof String;
            case BOOLEAN -> value instanceof Boolean;
        };
    }
}
