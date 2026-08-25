package synanton.extraction.spi.port;

import java.util.Optional;

/**
 * Port for storing and looking up idempotency records.
 *
 * <p>An idempotency record associates a caller-supplied key (scoped to a tenant) with the
 * platform-assigned operation ID from the first accepted submission. Subsequent submissions
 * with the same key return the existing operation ID without re-executing the operation.
 */
public interface IdempotencyStore {

    /**
     * Looks up the operation ID previously recorded for the given tenant and idempotency key.
     *
     * @param tenantId       the tenant scope for the lookup
     * @param idempotencyKey the caller-supplied idempotency key
     * @return an {@link Optional} containing the operation ID if a record exists, or empty
     */
    Optional<String> findOperationId(String tenantId, String idempotencyKey);

    /**
     * Records a mapping from a tenant-scoped idempotency key to an operation ID.
     *
     * <p>Implementations must be idempotent: storing the same mapping twice must not produce
     * an error.
     *
     * @param tenantId       the tenant scope for the record
     * @param idempotencyKey the caller-supplied idempotency key
     * @param operationId    the platform-assigned operation ID to associate with the key
     */
    void store(String tenantId, String idempotencyKey, String operationId);
}
