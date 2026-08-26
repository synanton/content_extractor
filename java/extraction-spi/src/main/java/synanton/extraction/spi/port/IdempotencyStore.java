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
     * An idempotency record associating a tenant-scoped key with an operation and request fingerprint.
     *
     * @param operationId  the platform-assigned operation identifier
     * @param requestHash  canonical hash of the immutable submit semantics
     */
    record Entry(String operationId, String requestHash) {
    }

    /**
     * Looks up the idempotency record for the given tenant and key.
     *
     * @param tenantId       the tenant scope for the lookup
     * @param idempotencyKey the caller-supplied idempotency key
     * @return an {@link Optional} containing the record if one exists, or empty
     */
    Optional<Entry> findEntry(String tenantId, String idempotencyKey);

    /**
     * Records a mapping from a tenant-scoped idempotency key to an operation ID and request hash.
     *
     * @param tenantId       the tenant scope for the record
     * @param idempotencyKey the caller-supplied idempotency key
     * @param requestHash    canonical hash of the immutable submit semantics
     * @param operationId    the platform-assigned operation ID to associate with the key
     */
    void store(String tenantId, String idempotencyKey, String requestHash, String operationId);
}
