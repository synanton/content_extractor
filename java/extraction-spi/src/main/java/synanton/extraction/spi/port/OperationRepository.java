package synanton.extraction.spi.port;

import synanton.extraction.spi.model.FeatureOutcome;
import synanton.extraction.spi.model.OperationState;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Port for persisting and querying extraction operations and their items.
 *
 * <p>All state-changing methods that transition state must validate the transition via
 * {@link OperationState#canTransitionTo(OperationState)} before applying the change.
 */
public interface OperationRepository {

    record ItemRecord(
            int itemIndex,
            String contentRefId,
            String mediaType,
            OperationState state,
            Map<String, FeatureOutcome> featureStates,
            String errorCode,
            String errorDiagnostic) {

        /**
         * Compact constructor that defensively copies the feature states map.
         */
        public ItemRecord {
            featureStates = Map.copyOf(featureStates);
        }
    }

    /**
     * Represents a complete extraction operation with all its items.
     *
     * @param id                the platform-assigned unique operation identifier
     * @param tenantId          the owning tenant
     * @param state             the current lifecycle state of the operation
     * @param idempotencyKey    the caller-supplied idempotency key
     * @param priority          the scheduling priority hint
     * @param createdAt         the instant the operation was created
     * @param updatedAt         the instant the operation was last modified
     * @param expiresAt         the instant after which the operation may be expired
     * @param admissionVerdict  the admission control verdict recorded at acceptance time
     * @param items             the individual extraction items within this operation
     */
    record ExtractionOperation(
            String id,
            String tenantId,
            OperationState state,
            String idempotencyKey,
            String priority,
            Instant createdAt,
            Instant updatedAt,
            Instant expiresAt,
            String admissionVerdict,
            List<ItemRecord> items) {

        /**
         * Compact constructor that defensively copies the items list.
         */
        public ExtractionOperation {
            items = List.copyOf(items);
        }
    }

    /**
     * Source object metadata persisted for an operation item.
     */
    record ItemSource(
            String bucket,
            String key,
            String version,
            String sha256,
            long sizeBytes) {
    }

    /**
     * Persists a newly admitted operation and its item source metadata.
     */
    void saveNewOperation(ExtractionOperation operation, List<ItemSource> itemSources);

    /**
     * Persists a new or updated {@link ExtractionOperation}.
     *
     * @param operation the operation to save
     */
    void save(ExtractionOperation operation);

    /**
     * Finds an operation by its platform-assigned identifier.
     *
     * @param operationId the operation identifier
     * @return an {@link Optional} containing the operation, or empty if not found
     */
    Optional<ExtractionOperation> findById(String operationId);

    /**
     * Finds a batch of operations by their identifiers, scoped to a tenant.
     *
     * @param tenantId     the owning tenant
     * @param operationIds the list of operation identifiers to fetch
     * @return the operations found; absent identifiers are silently omitted
     */
    List<ExtractionOperation> findByIds(String tenantId, List<String> operationIds);

    /**
     * Atomically transitions an operation from one state to another.
     *
     * @return {@code true} when exactly one row was updated
     */
    boolean transitionState(String operationId, OperationState from, OperationState to);

    /**
     * Claims the oldest queued operation for processing, transitioning it to {@code RUNNING}.
     *
     * @param leasedUntil lease expiry for the claimed operation
     * @return the claimed operation identifier, or empty when no work is available
     */
    Optional<String> claimNextQueued(Instant leasedUntil);

    /**
     * Extends the lease on a running operation.
     */
    void refreshLease(String operationId, Instant leasedUntil);

    /**
     * Counts non-terminal operations for a tenant.
     */
    int countActiveOperations(String tenantId);

    /**
     * Acquires a transaction-scoped advisory lock for tenant admission.
     */
    void acquireTenantAdmissionLock(String tenantId);

    /**
     * Assigns a monotonic completion sequence when an operation reaches a terminal state.
     */
    void assignCompletionSequence(String operationId);

    /**
     * Updates the state and feature outcomes of a single item within an operation.
     *
     * @param operationId     the operation containing the item
     * @param itemIndex       the zero-based index of the item to update
     * @param newState        the new lifecycle state for the item
     * @param featureStates   the updated feature outcomes (may be empty)
     * @param errorCode       the error code if the item failed, or {@code null}
     * @param errorDiagnostic the operator diagnostic if the item failed, or {@code null}
     */
    void updateItemState(String operationId, int itemIndex, OperationState newState,
                         Map<String, FeatureOutcome> featureStates,
                         String errorCode, String errorDiagnostic);

    /**
     * Lists completed operations for a tenant, paginated by an opaque cursor.
     *
     * @param tenantId the owning tenant
     * @param cursor   an opaque pagination cursor, or {@code null} for the first page
     * @param pageSize the maximum number of results to return
     * @return a page of completed operations in ascending creation order
     */
    List<ExtractionOperation> listCompleted(String tenantId, String cursor, int pageSize);

    Optional<ItemSource> findItemSource(String operationId, int itemIndex);

    Optional<Long> findCompletionSequence(String operationId);
}
