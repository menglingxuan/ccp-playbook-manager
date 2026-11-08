package ac.core.service.importflow;

import java.util.List;

/**
 * Import submission.
 *
 * @param uploadId               upload identifier
 * @param sheets                 sheets to import
 * @param force                  continue when the same file and sheet were imported before
 * @param includeManagedColumns  store the values of declared managed columns; defaults to true
 * @param includeUnmappedColumns store the values of columns that match no field; defaults to false
 * @param stopAtEmptyRow         stop at the first empty row instead of skipping it; defaults to false
 * @param stopAtFirstError       stop before the first row with import errors; defaults to true
 */
public record ImportCommitRequest(String uploadId, List<ImportSelection> sheets, boolean force,
        Boolean includeManagedColumns, Boolean includeUnmappedColumns, Boolean stopAtEmptyRow,
        Boolean stopAtFirstError) {

    public ImportCommitRequest {
        sheets = sheets == null ? List.of() : List.copyOf(sheets);
    }

    public boolean managedColumnsEnabled() {
        return includeManagedColumns == null || includeManagedColumns;
    }

    public boolean unmappedColumnsEnabled() {
        return includeUnmappedColumns != null && includeUnmappedColumns;
    }

    public boolean stopAtEmptyRowEnabled() {
        return stopAtEmptyRow != null && stopAtEmptyRow;
    }

    public boolean stopAtFirstErrorEnabled() {
        return stopAtFirstError == null || stopAtFirstError;
    }
}
