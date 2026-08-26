package synanton.extraction.domain.service;

import synanton.extraction.config.ExtractionGatewayProperties;
import synanton.extraction.domain.model.ExtractionItemCommand;
import synanton.extraction.domain.model.SubmitExtractionCommand;
import synanton.extraction.spi.model.ExtractionFailure;
import synanton.extraction.spi.model.FeatureOutcome;
import synanton.extraction.spi.model.OperationState;
import synanton.extraction.spi.port.IdempotencyStore;
import synanton.extraction.spi.port.OperationRepository;
import synanton.extraction.spi.port.OperationRepository.ExtractionOperation;
import synanton.extraction.spi.port.OperationRepository.ItemRecord;
import synanton.extraction.spi.port.OperationRepository.ItemSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public class SubmitExtractionService {

    public static final String ADMISSION_ADMITTED = "ADMISSION_ADMITTED";

    private final OperationRepository operationRepository;
    private final IdempotencyStore idempotencyStore;
    private final RequestCanonicalizer requestCanonicalizer;
    private final ExtractionGatewayProperties properties;
    private final Supplier<Instant> clock;

    public SubmitExtractionService(
            OperationRepository operationRepository,
            IdempotencyStore idempotencyStore,
            RequestCanonicalizer requestCanonicalizer,
            ExtractionGatewayProperties properties) {
        this(operationRepository, idempotencyStore, requestCanonicalizer, properties, Instant::now);
    }

    SubmitExtractionService(
            OperationRepository operationRepository,
            IdempotencyStore idempotencyStore,
            RequestCanonicalizer requestCanonicalizer,
            ExtractionGatewayProperties properties,
            Supplier<Instant> clock) {
        this.operationRepository = operationRepository;
        this.idempotencyStore = idempotencyStore;
        this.requestCanonicalizer = requestCanonicalizer;
        this.properties = properties;
        this.clock = clock;
    }

    public ExtractionOperation submitTransactional(SubmitExtractionCommand command) {
        String requestHash = requestCanonicalizer.canonicalize(command);

        Optional<IdempotencyStore.Entry> existing = idempotencyStore.findEntry(
                command.tenantId(), command.idempotencyKey());
        if (existing.isPresent()) {
            return resolveExisting(existing.get(), requestHash);
        }

        if (command.expiresAt() != null && clock.get().isAfter(command.expiresAt())) {
            return persistExpired(command, requestHash);
        }

        return admitWithinTransaction(command, requestHash);
    }

    private ExtractionOperation resolveExisting(IdempotencyStore.Entry entry, String requestHash) {
        if (!entry.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(entry.operationId());
        }
        return operationRepository.findById(entry.operationId())
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotency record references missing operation " + entry.operationId()));
    }

    private ExtractionOperation persistExpired(SubmitExtractionCommand command, String requestHash) {
        Instant now = clock.get();
        String operationId = UUID.randomUUID().toString();
        ExtractionFailure expired = ExtractionFailure.expired("expires_at is in the past");
        ExtractionOperation operation = new ExtractionOperation(
                operationId,
                command.tenantId(),
                OperationState.EXPIRED,
                command.idempotencyKey(),
                command.priority(),
                now,
                now,
                command.expiresAt(),
                ADMISSION_ADMITTED,
                buildTerminalItems(command, OperationState.EXPIRED, expired));

        persistNewOperation(operation, command, requestHash);
        operationRepository.assignCompletionSequence(operationId);
        return operation;
    }

    ExtractionOperation admitWithinTransaction(SubmitExtractionCommand command, String requestHash) {
        operationRepository.acquireTenantAdmissionLock(command.tenantId());

        Optional<IdempotencyStore.Entry> existing = idempotencyStore.findEntry(
                command.tenantId(), command.idempotencyKey());
        if (existing.isPresent()) {
            return resolveExisting(existing.get(), requestHash);
        }

        int maxConcurrent = properties.getAsync().getMaxConcurrentOperationsPerTenant();
        if (operationRepository.countActiveOperations(command.tenantId()) >= maxConcurrent) {
            throw new CapacityRejectedException(command.tenantId());
        }

        Instant now = clock.get();
        String operationId = UUID.randomUUID().toString();
        ExtractionOperation operation = new ExtractionOperation(
                operationId,
                command.tenantId(),
                OperationState.QUEUED,
                command.idempotencyKey(),
                command.priority(),
                now,
                now,
                command.expiresAt(),
                ADMISSION_ADMITTED,
                buildQueuedItems(command));

        persistNewOperation(operation, command, requestHash);
        return operation;
    }

    private void persistNewOperation(
            ExtractionOperation operation,
            SubmitExtractionCommand command,
            String requestHash) {
        List<ItemSource> sources = command.items().stream()
                .map(item -> new ItemSource(
                        item.source().bucket(),
                        item.source().key(),
                        item.source().version(),
                        item.source().sha256(),
                        item.source().sizeBytes()))
                .toList();
        operationRepository.saveNewOperation(operation, sources);
        idempotencyStore.store(
                command.tenantId(),
                command.idempotencyKey(),
                requestHash,
                operation.id());
    }

    private List<ItemRecord> buildQueuedItems(SubmitExtractionCommand command) {
        List<ItemRecord> items = new ArrayList<>();
        for (int i = 0; i < command.items().size(); i++) {
            ExtractionItemCommand item = command.items().get(i);
            items.add(new ItemRecord(
                    i,
                    item.contentRefId(),
                    item.mediaType(),
                    OperationState.QUEUED,
                    Map.of(),
                    null,
                    null));
        }
        return items;
    }

    private List<ItemRecord> buildTerminalItems(
            SubmitExtractionCommand command,
            OperationState state,
            ExtractionFailure failure) {
        List<ItemRecord> items = new ArrayList<>();
        for (int i = 0; i < command.items().size(); i++) {
            ExtractionItemCommand item = command.items().get(i);
            items.add(new ItemRecord(
                    i,
                    item.contentRefId(),
                    item.mediaType(),
                    state,
                    Map.of("text", FeatureOutcome.NOT_APPLICABLE),
                    failure.errorCode(),
                    failure.diagnostic()));
        }
        return items;
    }

    public static class IdempotencyConflictException extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        private final String operationId;

        public IdempotencyConflictException(String operationId) {
            super("Idempotency key reused with different request semantics");
            this.operationId = operationId;
        }

        public String operationId() {
            return operationId;
        }
    }

    public static class CapacityRejectedException extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        private final String tenantId;

        public CapacityRejectedException(String tenantId) {
            super("Tenant capacity saturated: " + tenantId);
            this.tenantId = tenantId;
        }

        public String tenantId() {
            return tenantId;
        }
    }
}
