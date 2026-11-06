package ac.domain.layout;

/** Semantic meaning of a managed column. */
public enum ManagedKind {
    /** Maps to {@code test_case.test_case_id}. */
    TESTCASE_ID,
    /** Maps to the well known remarks key. */
    REMARKS,
    /** Any other business column, kept verbatim. */
    PLAIN
}
