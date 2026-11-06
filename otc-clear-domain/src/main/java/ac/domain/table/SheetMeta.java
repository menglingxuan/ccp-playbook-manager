package ac.domain.table;

/** Sheet level metadata used by the import wizard. */
public record SheetMeta(int index, String name, int rowCount, int columnCount, boolean hidden) {
}
