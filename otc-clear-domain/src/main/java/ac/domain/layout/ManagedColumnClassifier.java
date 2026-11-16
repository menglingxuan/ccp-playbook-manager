package ac.domain.layout;

import ac.domain.product.FieldDef;
import ac.domain.product.HeaderNormalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Classifies columns that sit outside the trade block. */
public final class ManagedColumnClassifier {

    private static final Set<String> TESTCASE_ID_NAMES = Set.of("testcaseid", "testcase", "caseid");
    private static final Set<String> REMARKS_NAMES =
            Set.of("remarks", "remark", "comment", "comments", "note", "notes");
    public static final String DEFAULT_TESTCASE_ID_KEY = "_TestCaseId";
    public static final String DEFAULT_REMARKS_KEY = "_Remarks";

    private ManagedColumnClassifier() {
    }

    public static ManagedKind classify(String header) {
        String normalized = HeaderNormalizer.normalize(header);
        if (TESTCASE_ID_NAMES.contains(normalized)) {
            return ManagedKind.TESTCASE_ID;
        }
        if (REMARKS_NAMES.contains(normalized)) {
            return ManagedKind.REMARKS;
        }
        return ManagedKind.PLAIN;
    }

    /**
     * Builds a unique storage key for a managed column. Duplicates get a {@code #n} suffix so no
     * value is silently dropped.
     */
    public static String storageKey(ManagedKind kind, String header, Set<String> usedKeys) {
        String base = switch (kind) {
            case TESTCASE_ID -> DEFAULT_TESTCASE_ID_KEY;
            case REMARKS -> DEFAULT_REMARKS_KEY;
            case PLAIN -> header == null || header.isBlank() ? "Column" : header.trim();
        };
        String candidate = base;
        int suffix = 2;
        while (usedKeys.contains(candidate)) {
            candidate = base + "#" + suffix;
            suffix++;
        }
        return candidate;
    }

    /**
     * Field key to {@code managed_json} storage key for every managed field of the product, in
     * declaration order so duplicated headers get the same {@code #n} suffix as the import layout.
     */
    public static Map<String, String> storageKeys(List<FieldDef> fields) {
        Map<String, String> keys = new LinkedHashMap<>();
        Set<String> used = new LinkedHashSet<>();
        for (FieldDef field : fields) {
            if (!field.isManaged()) {
                continue;
            }
            String storageKey = storageKey(classify(field.header()), field.header(), used);
            used.add(storageKey);
            keys.put(field.key(), storageKey);
        }
        return keys;
    }
}
