package org.synanton.extraction.domain.service;

import org.synanton.extraction.spi.model.AdapterResult;
import org.synanton.extraction.spi.port.IdempotencyStore;
import org.synanton.extraction.spi.port.OperationRepository;
import org.synanton.extraction.spi.port.OperationRepository.ExtractionOperation;
import org.synanton.extraction.spi.port.OperationRepository.ItemSource;
import org.synanton.extraction.spi.port.ResultStore;

import java.util.List;

/**
 * The one place that writes an admitted operation to durable storage.
 *
 * <p>Both admission paths call this helper: asynchronous submit (operation +
 * idempotency, no result yet) and synchronous extraction (operation + result +
 * idempotency). Keeping the write orchestration here avoids a second copy of
 * the persistence logic and keeps the two paths independently testable.
 *
 * <p>Callers own the transaction boundary.
 */
public final class OperationPersistence {

    private final OperationRepository operationRepository;
    private final ResultStore resultStore;
    private final IdempotencyStore idempotencyStore;

    public OperationPersistence(
            OperationRepository operationRepository,
            ResultStore resultStore,
            IdempotencyStore idempotencyStore) {
        this.operationRepository = operationRepository;
        this.resultStore = resultStore;
        this.idempotencyStore = idempotencyStore;
    }

    /**
     * Persists a newly admitted operation and its item source metadata.
     *
     * @param operation   the operation to persist
     * @param itemSources source metadata, one entry per item, in item order
     * @param result      the single-item result to store, or {@code null} when the
     *                    result is written later (async worker)
     * @param requestHash canonical request hash for the idempotency record
     */
    public void persist(
            ExtractionOperation operation,
            List<ItemSource> itemSources,
            AdapterResult result,
            String requestHash) {
        if (operation.items().size() != itemSources.size()) {
            throw new IllegalArgumentException(
                    "operation has " + operation.items().size()
                            + " items but " + itemSources.size() + " sources");
        }

        operationRepository.saveNewOperation(operation, itemSources);

        if (result != null) {
            // Sync extraction is single-item; item 0 is the contract.
            storeResult(operation.tenantId(), operation.id(), 0, result);
        }

        if (operation.state().isTerminal()) {
            operationRepository.assignCompletionSequence(operation.id());
        }

        String key = operation.idempotencyKey();
        if (key != null && !key.isBlank()) {
            if (requestHash == null || requestHash.isBlank()) {
                throw new IllegalArgumentException("requestHash is required when an idempotency key is present");
            }
            idempotencyStore.store(operation.tenantId(), key, requestHash, operation.id());
        }
    }

    /**
     * Stores one item's result. The sync path and the async worker both call
     * this method, so there is a single result writer.
     */
    public void storeResult(String tenantId, String operationId, int itemIndex, AdapterResult result) {
        if (resultStore == null) {
            throw new IllegalStateException("ResultStore is required to persist a result");
        }
        resultStore.store(tenantId, operationId, itemIndex, result);
    }
}
