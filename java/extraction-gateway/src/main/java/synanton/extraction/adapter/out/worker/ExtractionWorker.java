package synanton.extraction.adapter.out.worker;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import synanton.extraction.spi.port.ResultStore;
import synanton.extraction.config.ExtractionGatewayProperties;
import synanton.extraction.domain.model.SyncExtractionOutcome;
import synanton.extraction.domain.model.SyncExtractionOutcome.OutcomeStatus;
import synanton.extraction.domain.service.ExtractSyncService;
import synanton.extraction.spi.model.AdapterResult;
import synanton.extraction.spi.model.ExtractionFailure;
import synanton.extraction.spi.model.ExtractionOptions;
import synanton.extraction.spi.model.FeatureOutcome;
import synanton.extraction.spi.model.ObjectRef;
import synanton.extraction.spi.model.OperationState;
import synanton.extraction.spi.port.OperationRepository;
import synanton.extraction.spi.port.OperationRepository.ExtractionOperation;
import synanton.extraction.spi.port.OperationRepository.ItemRecord;
import synanton.extraction.spi.port.OperationRepository.ItemSource;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class ExtractionWorker {

    private static final EnumSet<OperationState> SUCCESS_ITEM_STATES =
            EnumSet.of(OperationState.COMPLETED, OperationState.PARTIAL);

    private final OperationRepository operationRepository;
    private final ExtractSyncService extractSyncService;
    private final ResultStore resultStore;
    private final ExtractionGatewayProperties properties;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ExtractionWorker(
            OperationRepository operationRepository,
            ExtractSyncService extractSyncService,
            ResultStore resultStore,
            ExtractionGatewayProperties properties) {
        this.operationRepository = operationRepository;
        this.extractSyncService = extractSyncService;
        this.resultStore = resultStore;
        this.properties = properties;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "extraction-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        long pollIntervalMs = properties.getAsync().getWorkerPollIntervalMs();
        scheduler.scheduleWithFixedDelay(this::drainOnce, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        scheduler.shutdownNow();
    }

    void drainOnce() {
        if (!running.get()) {
            return;
        }
        Duration leaseTimeout = Duration.ofSeconds(properties.getAsync().getLeaseTimeoutSeconds());
        Instant leasedUntil = Instant.now().plus(leaseTimeout);
        Optional<String> claimed = operationRepository.claimNextQueued(leasedUntil);
        claimed.ifPresent(this::processOperation);
    }

    void processClaimedOperation(String operationId) {
        processOperation(operationId);
    }

    private void processOperation(String operationId) {
        Optional<ExtractionOperation> loaded = operationRepository.findById(operationId);
        if (loaded.isEmpty()) {
            return;
        }

        ExtractionOperation operation = loaded.get();
        if (operation.state() != OperationState.RUNNING) {
            return;
        }

        if (isExpiredBeforeExecution(operation)) {
            expireOperation(operation);
            return;
        }

        int succeeded = 0;
        int failed = 0;
        for (ItemRecord item : operation.items()) {
            if (processItem(operation, item)) {
                succeeded++;
            } else {
                failed++;
            }
            operationRepository.refreshLease(
                    operationId,
                    Instant.now().plusSeconds(properties.getAsync().getLeaseTimeoutSeconds()));
        }

        OperationState terminal = resolveTerminalState(succeeded, failed, operation.items().size());
        operationRepository.transitionState(operationId, OperationState.RUNNING, terminal);
        operationRepository.assignCompletionSequence(operationId);
        log.info("Operation {} reached terminal state {}", operationId, terminal);
    }

    private boolean processItem(ExtractionOperation operation, ItemRecord item) {
        Optional<ItemSource> sourceOpt = operationRepository.findItemSource(operation.id(), item.itemIndex());
        if (sourceOpt.isEmpty()) {
            markItemFailed(operation.id(), item, ExtractionFailure.internalError("Missing item source metadata"));
            return false;
        }

        ItemSource source = sourceOpt.get();
        ObjectRef objectRef = new ObjectRef(
                source.bucket(),
                source.key(),
                source.version(),
                source.sha256(),
                source.sizeBytes());

        SyncExtractionOutcome outcome = extractSyncService.extract(
                operation.id(),
                operation.tenantId(),
                operation.idempotencyKey(),
                item.contentRefId(),
                objectRef,
                item.mediaType(),
                ExtractionOptions.defaults(),
                operation.expiresAt());

        OperationState itemState = mapItemState(outcome.status());
        ExtractionFailure failure = outcome.failure();
        operationRepository.updateItemState(
                operation.id(),
                item.itemIndex(),
                itemState,
                outcome.featureStates(),
                failure == null ? null : failure.errorCode(),
                failure == null ? null : failure.diagnostic());

        AdapterResult adapterResult = outcome.document() != null
                ? AdapterResult.success(outcome.document(), outcome.featureStates())
                : AdapterResult.failed(
                        failure != null ? failure : ExtractionFailure.internalError("Unknown failure"),
                        outcome.featureStates());
        resultStore.store(
                operation.tenantId(),
                operation.id(),
                item.itemIndex(),
                adapterResult);

        return SUCCESS_ITEM_STATES.contains(itemState);
    }

    private void markItemFailed(String operationId, ItemRecord item, ExtractionFailure failure) {
        operationRepository.updateItemState(
                operationId,
                item.itemIndex(),
                OperationState.FAILED,
                Map.of("text", FeatureOutcome.FAILED),
                failure.errorCode(),
                failure.diagnostic());
    }

    private static OperationState mapItemState(OutcomeStatus status) {
        return switch (status) {
            case COMPLETED -> OperationState.COMPLETED;
            case PARTIAL -> OperationState.PARTIAL;
            case EXPIRED -> OperationState.EXPIRED;
            case FAILED -> OperationState.FAILED;
        };
    }

    private static OperationState resolveTerminalState(int succeeded, int failed, int total) {
        if (succeeded == total) {
            return OperationState.COMPLETED;
        }
        if (succeeded > 0) {
            return OperationState.PARTIAL;
        }
        return OperationState.FAILED;
    }

    private static boolean isExpiredBeforeExecution(ExtractionOperation operation) {
        return operation.expiresAt() != null && Instant.now().isAfter(operation.expiresAt());
    }

    private void expireOperation(ExtractionOperation operation) {
        for (ItemRecord item : operation.items()) {
            ExtractionFailure expired = ExtractionFailure.expired("Operation expired before execution");
            operationRepository.updateItemState(
                    operation.id(),
                    item.itemIndex(),
                    OperationState.EXPIRED,
                    Map.of("text", FeatureOutcome.NOT_APPLICABLE),
                    expired.errorCode(),
                    expired.diagnostic());
        }
        operationRepository.transitionState(operation.id(), OperationState.RUNNING, OperationState.EXPIRED);
        operationRepository.assignCompletionSequence(operation.id());
    }
}
