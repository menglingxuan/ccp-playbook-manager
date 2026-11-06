package ac.core.service.clone;

/**
 * Result of a clone run.
 *
 * @param batchId new batch id
 * @param created number of created cases
 */
public record CloneResult(String batchId, int created) {
}
