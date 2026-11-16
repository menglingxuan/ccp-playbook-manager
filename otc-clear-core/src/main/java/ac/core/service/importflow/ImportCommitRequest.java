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
 * @param taskName               optional task name or task id: an existing task is looked up by id
 *                               first, then by name, and receives the new batches; otherwise a task
 *                               with that name is created; blank keeps the default task naming
 */
public record ImportCommitRequest(String uploadId, List<ImportSelection> sheets, boolean force,
        Boolean includeManagedColumns, Boolean includeUnmappedColumns, Boolean stopAtEmptyRow,
        Boolean stopAtFirstError, String taskName) {

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

    /** Requested task name without surrounding blanks, empty when the default naming applies. */
    public String requestedTaskName() {
        return taskName == null ? "" : taskName.trim();
    }
}
