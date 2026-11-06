package ac.domain.export;

/** Controls which management columns are written to the export file. */
public enum ManagedColumnScope {
    /** Only trade fields (plus the test case id when it comes from the file). */
    NONE,
    /** Trade fields plus the well known {@code _TestCaseId} column. */
    KNOWN,
    /** Left managed columns, trade fields and right managed columns in source order. */
    ALL
}
