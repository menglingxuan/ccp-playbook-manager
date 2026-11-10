package ac.core.persistence;

import ac.core.common.PageQuery;
import java.time.LocalDate;

/**
 * Audit panel filter.
 *
 * @param module     functional module code, {@code null} for all modules
 * @param entityType audited object, {@code null} for all objects
 * @param action     action code, {@code null} for all actions
 * @param query      free text matched against entity id, action, actor and detail
 * @param from       first display date (inclusive), {@code null} for no lower bound
 * @param to         last display date (inclusive), {@code null} for no upper bound
 * @param page       page request
 */
public record AuditLogFilter(String module, String entityType, String action, String query,
        LocalDate from, LocalDate to, PageQuery page) {

    public AuditLogFilter {
        page = page == null ? PageQuery.of(1, PageQuery.DEFAULT_SIZE) : page;
    }
}
