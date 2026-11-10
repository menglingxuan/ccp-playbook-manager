package ac.core.service.audit;

/**
 * Functional module an audit entry belongs to.
 *
 * <p>Only the product definition and the field meta dictionary are audited for now; the codes are
 * part of the audit API so they must stay stable.
 */
public enum AuditModule {
    PRODUCT_DEFINITION,
    META_DICTIONARY
}
