package ac.domain.layout;

import ac.domain.product.HeaderNormalizer;
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
}
