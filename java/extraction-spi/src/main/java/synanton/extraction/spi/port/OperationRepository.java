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

    /**
     * Represents one item (content ref) within a multi-item extraction operation.
     *
     * @param itemIndex       zero-based position of the item within the operation
     * @param contentRefId    the caller-supplied content reference identifier
     * @param mediaType       the IANA media type of the source content
     * @param state           the current lifecycle state of the item
     * @param featureStates   the outcome of each requested extraction feature for this item
     * @param errorCode       the error code if the item failed (null otherwise)
     * @param errorDiagnostic the operator-level diagnostic if the item failed (null otherwise)
     */
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
     * <p>Validates the transition via {@link OperationState#canTransitionTo(OperationState)}
     * before applying the change. Throws if the current persisted state does not match {@code from}
     * or if the transition is not permitted.
     *
     * @param operationId the operation to update
     * @param from        the expected current state
     * @param to          the target state
     * @throws IllegalStateException    if the {@code from → to} transition is not permitted
     * @throws IllegalArgumentException if the current state does not match {@code from}
     */
    void transitionState(String operationId, OperationState from, OperationState to);

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
}
