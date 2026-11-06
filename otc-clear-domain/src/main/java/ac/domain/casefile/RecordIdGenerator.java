package ac.domain.casefile;

import ac.domain.ids.Hashing;
import ac.domain.product.FieldCodec;
import ac.domain.product.FieldDef;
import ac.domain.product.ProductSchema;
import java.util.Map;

/** Deterministic generation of the three record identifiers. */
public final class RecordIdGenerator {

    public static final int DEFAULT_HASH_LENGTH = 32;
    private static final char UNIT_SEPARATOR = '\u001F';

    private RecordIdGenerator() {
    }

    /** Original record id: stable for the same file name, sheet name and row number. */
    public static String originalId(String fileName, String sheetName, int sourceRow, int hashLength) {
        String raw = safe(fileName) + '|' + safe(sheetName) + '|' + sourceRow;
        return Hashing.prefix(Hashing.sha256Hex(raw), hashLength);
    }

    /**
     * Business record id: hashes the configured business fields. A formula cell contributes its
     * formula text so that recalculated values do not change the identity.
     */
    public static String businessId(ProductSchema schema, Map<String, Object> data,
            Map<String, String> formulas, int hashLength) {
        StringBuilder sb = new StringBuilder();
        for (FieldDef field : schema.businessFields()) {
            String value;
            if (formulas.containsKey(field.key())) {
                value = formulas.get(field.key());
            } else {
                value = FieldCodec.canonical(field, data.get(field.key()));
            }
            sb.append(field.key()).append('=').append(value == null ? "" : value).append(UNIT_SEPARATOR);
        }
        if (sb.length() == 0) {
            return "";
        }
        return Hashing.prefix(Hashing.sha256Hex(sb.toString()), hashLength);
    }

    /** Generated test case id derived from the business record id. */
    public static String generatedTestCaseId(String businessRecordId) {
        if (businessRecordId == null || businessRecordId.isBlank()) {
            throw new IllegalArgumentException("Business record id is required to generate a test case id");
        }
        return "TC-" + Hashing.prefix(businessRecordId, 12).toUpperCase(java.util.Locale.ROOT);
    }

    /** Clone original id: traceable back to the source record and copy sequence. */
    public static String cloneOriginalId(String sourceOriginalId, int sequence, int hashLength) {
        String raw = "CLONE|" + safe(sourceOriginalId) + '|' + sequence;
        return Hashing.prefix(Hashing.sha256Hex(raw), hashLength);
    }

    /** Stable identifier for external output files. */
    public static String shortId(String value, int length) {
        return Hashing.prefix(Hashing.sha256Hex(safe(value)), length);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
