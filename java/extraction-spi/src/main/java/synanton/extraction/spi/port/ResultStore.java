package synanton.extraction.spi.port;

import synanton.extraction.spi.model.AdapterResult;

import java.util.Optional;

/**
 * Port for persisting and retrieving adapter results.
 *
 * <p>Results are addressed by the combination of tenant, operation, and item index, matching the
 * multi-item structure of an extraction operation.
 */
public interface ResultStore {

    /**
     * Persists the adapter result for a specific item within an operation.
     *
     * @param tenantId    the owning tenant
     * @param operationId the platform-assigned operation identifier
     * @param itemIndex   the zero-based index of the item within the operation
     * @param result      the adapter result to store (success or failure)
     */
    void store(String tenantId, String operationId, int itemIndex, AdapterResult result);

    /**
     * Loads a previously stored adapter result for a specific item.
     *
     * @param tenantId    the owning tenant
     * @param operationId the platform-assigned operation identifier
     * @param itemIndex   the zero-based index of the item within the operation
     * @return an {@link Optional} containing the stored result, or empty if not found
     */
    Optional<AdapterResult> load(String tenantId, String operationId, int itemIndex);
}
