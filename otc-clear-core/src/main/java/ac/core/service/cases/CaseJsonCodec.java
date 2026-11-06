package ac.core.service.cases;

import ac.core.common.JsonSupport;
import ac.domain.casefile.CaseRow;
import ac.domain.casefile.UnresolvedFormula;
import ac.domain.product.FieldCodec;
import ac.domain.product.FieldDef;
import ac.domain.product.ProductSchema;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Converts domain case rows into the JSON payloads stored in {@code test_case}. */
@Component
public class CaseJsonCodec {

    private final JsonSupport json;

    public CaseJsonCodec(JsonSupport json) {
        this.json = json;
    }

    /** Values ready for JSON serialization: dates become ISO strings, decimals stay numeric. */
    public Map<String, Object> toJsonValues(ProductSchema schema, Map<String, Object> data) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof LocalDate date) {
                values.put(entry.getKey(), date.toString());
            } else if (value != null) {
                values.put(entry.getKey(), value);
            }
        }
        return values;
    }

    public String formulaJson(ProductSchema schema, Map<String, String> formulas,
            List<UnresolvedFormula> unresolved) {
        Map<String, String> tradeFormulas = new LinkedHashMap<>();
        Map<String, String> managedFormulas = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : formulas.entrySet()) {
            if (schema.byKey().containsKey(entry.getKey())) {
                tradeFormulas.put(entry.getKey(), entry.getValue());
            } else {
                managedFormulas.put(entry.getKey(), entry.getValue());
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fields", tradeFormulas);
        payload.put("managed", managedFormulas);
        if (unresolved != null && !unresolved.isEmpty()) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (UnresolvedFormula item : unresolved) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("fieldKey", item.key());
                entry.put("column", item.column());
                entry.put("reason", item.reason());
                items.add(entry);
            }
            payload.put("_unresolved", items);
        }
        return json.toJson(payload);
    }

    /** Rebuilds {@code formula_json} while carrying over previously unresolved entries. */
    public String formulaJson(ProductSchema schema, Map<String, String> formulas, String existingFormulaJson) {
        Map<String, String> tradeFormulas = new LinkedHashMap<>();
        Map<String, String> managedFormulas = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : formulas.entrySet()) {
            if (schema.byKey().containsKey(entry.getKey())) {
                tradeFormulas.put(entry.getKey(), entry.getValue());
            } else {
                managedFormulas.put(entry.getKey(), entry.getValue());
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fields", tradeFormulas);
        payload.put("managed", managedFormulas);
        Map<String, Object> existing = json.toMapOrEmpty(existingFormulaJson);
        Object unresolved = existing.get("_unresolved");
        if (unresolved instanceof List<?> list && !list.isEmpty()) {
            payload.put("_unresolved", unresolved);
        }
        return json.toJson(payload);
    }

    public String managedJson(Map<String, String> managed) {
        return json.toJson(managed == null ? Map.of() : managed);
    }

    public String extrasJson(Map<String, String> extras) {
        return json.toJson(extras == null ? Map.of() : extras);
    }

    public String dataJson(ProductSchema schema, Map<String, Object> data) {
        return json.toJson(toJsonValues(schema, data));
    }

    public String dataJson(ProductSchema schema, CaseRow row) {
        return dataJson(schema, row.data());
    }

    /** Display text of one stored value. */
    public String displayValue(ProductSchema schema, String fieldKey, Object value) {
        if (value == null) {
            return "";
        }
        FieldDef field = schema.byKey().get(fieldKey);
        if (field == null) {
            return String.valueOf(value);
        }
        if (value instanceof String text) {
            try {
                Object parsed = FieldCodec.parse(field, text);
                return FieldCodec.format(field, parsed);
            } catch (RuntimeException e) {
                return text;
            }
        }
        return FieldCodec.format(field, value);
    }

    /** Returns the formula map for the trade fields stored in {@code formula_json}. */
    public Map<String, String> tradeFormulas(String formulaJson) {
        Map<String, Object> payload = json.toMapOrEmpty(formulaJson);
        Object fields = payload.get("fields");
        Map<String, String> result = new LinkedHashMap<>();
        if (fields instanceof Map<?, ?> map) {
            map.forEach((key, value) -> result.put(String.valueOf(key), String.valueOf(value)));
        }
        return result;
    }

    /** Returns the formula map for managed columns stored in {@code formula_json}. */
    public Map<String, String> managedFormulas(String formulaJson) {
        Map<String, Object> payload = json.toMapOrEmpty(formulaJson);
        Object managed = payload.get("managed");
        Map<String, String> result = new LinkedHashMap<>();
        if (managed instanceof Map<?, ?> map) {
            map.forEach((key, value) -> result.put(String.valueOf(key), String.valueOf(value)));
        }
        return result;
    }

    /** Unresolved formula entries for diagnostics. */
    public List<Map<String, Object>> unresolvedFormulas(String formulaJson) {
        Map<String, Object> payload = json.toMapOrEmpty(formulaJson);
        Object items = payload.get("_unresolved");
        List<Map<String, Object>> result = new ArrayList<>();
        if (items instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    map.forEach((key, value) -> entry.put(String.valueOf(key), value));
                    result.add(entry);
                }
            }
        }
        return result;
    }
}
