package org.synanton.extraction.adapter.in.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.synanton.extraction.spi.model.ExtractionFailure;
import org.synanton.extraction.adapter.out.persistence.JdbcIdempotencyStore.DuplicateIdempotencyKeyException;
import org.synanton.extraction.adapter.out.persistence.JdbcIdempotencyStore.IdempotencyStoreUnavailableException;
import org.synanton.extraction.adapter.out.persistence.OperationAdmissionExecutor;
import org.synanton.extraction.config.ExtractionGatewayProperties;
import org.synanton.extraction.domain.model.ExtractionItemCommand;
import org.synanton.extraction.domain.model.SubmitExtractionCommand;
import org.synanton.extraction.domain.model.SyncExtractionOutcome;
import org.synanton.extraction.domain.service.CancelOperationService;
import org.synanton.extraction.domain.service.CapacityService;
import org.synanton.extraction.domain.service.ExtractSyncService;
import org.synanton.extraction.domain.service.OperationPersistence;
import org.synanton.extraction.domain.service.OperationQueryService;
import org.synanton.extraction.domain.service.RequestCanonicalizer;
import org.synanton.extraction.domain.service.SubmitExtractionService;
import org.synanton.extraction.spi.model.AdapterResult;
import org.synanton.extraction.spi.model.OperationState;
import org.synanton.extraction.spi.port.IdempotencyStore;
import org.synanton.extraction.spi.port.ModalityAdapter;
import org.synanton.extraction.spi.port.OperationRepository;
import org.synanton.extraction.spi.port.OperationRepository.ItemRecord;
import org.synanton.extraction.spi.port.OperationRepository.ItemSource;
import org.synanton.extraction.spi.port.ResultStore;
import org.synanton.extraction.v1.*;
import org.synanton.extraction.v1.validation.ExtractionRequestValidator;
import org.synanton.extraction.v1.validation.FieldViolation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class ExtractionGrpcAdapter extends ExtractionServiceGrpc.ExtractionServiceImplBase {

    private final ExtractSyncService extractSyncService;
    private final OperationAdmissionExecutor admissionExecutor;
    private final OperationQueryService operationQueryService;
    private final CancelOperationService cancelOperationService;
    private final CapacityService capacityService;
    private final OperationRepository operationRepository;
    private final ResultStore resultStore;
    private final OperationPersistence operationPersistence;
    private final IdempotencyStore idempotencyStore;
    private final RequestCanonicalizer requestCanonicalizer;
    private final TransactionTemplate transactionTemplate;
    private final ExtractionGatewayProperties properties;

    public ExtractionGrpcAdapter(
            ExtractSyncService extractSyncService,
            OperationAdmissionExecutor admissionExecutor,
            OperationQueryService operationQueryService,
            CancelOperationService cancelOperationService,
            CapacityService capacityService,
            OperationRepository operationRepository,
            ResultStore resultStore,
            OperationPersistence operationPersistence,
            IdempotencyStore idempotencyStore,
            RequestCanonicalizer requestCanonicalizer,
            TransactionTemplate transactionTemplate,
            ExtractionGatewayProperties properties) {
        this.extractSyncService = extractSyncService;
        this.admissionExecutor = admissionExecutor;
        this.operationQueryService = operationQueryService;
        this.cancelOperationService = cancelOperationService;
        this.capacityService = capacityService;
        this.operationRepository = operationRepository;
        this.resultStore = resultStore;
        this.operationPersistence = operationPersistence;
        this.idempotencyStore = idempotencyStore;
        this.requestCanonicalizer = requestCanonicalizer;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
    }

    @Override
    public void extractSync(
            SubmitExtractionRequest request,
            StreamObserver<ExtractionResult> responseObserver) {
        List<FieldViolation> violations = ExtractionRequestValidator.validate(request);
        if (!violations.isEmpty()) {
            rejectInvalidArgument(responseObserver, violations);
            return;
        }

        SubmitExtractionCommand command = RequestMapper.toCommand(request);
        String requestHash = requestCanonicalizer.canonicalize(command);
        String tenantId = command.tenantId();
        String idempotencyKey = command.idempotencyKey();

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                Optional<IdempotencyStore.Entry> existing =
                        idempotencyStore.findEntry(tenantId, idempotencyKey);
                if (existing.isPresent()) {
                    returnExistingOperation(tenantId, existing.get(), requestHash, responseObserver);
                    return;
                }
            } catch (IdempotencyStoreUnavailableException e) {
                responseObserver.onError(Status.UNAVAILABLE
                        .withDescription("Idempotency store unavailable")
                        .asRuntimeException());
                return;
            }
        }

        String operationId = UUID.randomUUID().toString();
        ExtractionItemCommand item = command.items().get(0);
        SyncExtractionOutcome outcome = extractSyncService.extract(
                operationId,
                tenantId,
                idempotencyKey,
                item.contentRefId(),
                item.source(),
                item.mediaType(),
                item.options(),
                command.expiresAt());

        OperationRepository.ExtractionOperation operation =
                toPersistedOperation(operationId, command, outcome);
        List<ItemSource> sources = List.of(new ItemSource(
                item.source().bucket(),
                item.source().key(),
                item.source().version(),
                item.source().sha256(),
                item.source().sizeBytes()));
        AdapterResult storedResult = toAdapterResult(outcome);

        try {
            transactionTemplate.execute(status -> {
                operationPersistence.persist(operation, sources, storedResult, requestHash);
                return null;
            });
        } catch (DuplicateIdempotencyKeyException duplicate) {
            // A concurrent submit won the idempotency race; return its operation.
            try {
                returnExistingOperation(
                        tenantId,
                        idempotencyStore.findEntry(tenantId, idempotencyKey)
                                .orElseThrow(() -> duplicate),
                        requestHash,
                        responseObserver);
            } catch (IdempotencyStoreUnavailableException e) {
                responseObserver.onError(Status.UNAVAILABLE
                        .withDescription("Idempotency store unavailable")
                        .asRuntimeException());
            }
            return;
        }

        responseObserver.onNext(ResultMapper.toResult(outcome, request));
        responseObserver.onCompleted();
    }

    private OperationRepository.ExtractionOperation toPersistedOperation(
            String operationId,
            SubmitExtractionCommand command,
            SyncExtractionOutcome outcome) {
        OperationState state = switch (outcome.status()) {
            case COMPLETED -> OperationState.COMPLETED;
            case PARTIAL -> OperationState.PARTIAL;
            case FAILED -> OperationState.FAILED;
            case EXPIRED -> OperationState.EXPIRED;
        };
        ExtractionItemCommand item = command.items().get(0);
        ItemRecord record = new ItemRecord(
                0,
                item.contentRefId(),
                item.mediaType(),
                state,
                outcome.featureStates(),
                outcome.failure() != null ? outcome.failure().errorCode() : null,
                outcome.failure() != null ? outcome.failure().diagnostic() : null);
        Instant now = Instant.now();
        return new OperationRepository.ExtractionOperation(
                operationId,
                command.tenantId(),
                state,
                command.idempotencyKey(),
                command.priority(),
                now,
                now,
                command.expiresAt(),
                SubmitExtractionService.ADMISSION_ADMITTED,
                List.of(record));
    }

    private static AdapterResult toAdapterResult(SyncExtractionOutcome outcome) {
        if (outcome.document() != null) {
            return AdapterResult.success(outcome.document(), outcome.featureStates());
        }
        ExtractionFailure failure = outcome.failure() != null
                ? outcome.failure()
                : ExtractionFailure.internalError("Unknown failure");
        return AdapterResult.failed(failure, outcome.featureStates());
    }

    private void returnExistingOperation(
            String tenantId,
            IdempotencyStore.Entry entry,
            String requestHash,
            StreamObserver<ExtractionResult> responseObserver) {
        if (!entry.requestHash().equals(requestHash)) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Idempotency key reused with different request semantics")
                    .asRuntimeException());
            return;
        }

        Optional<OperationRepository.ExtractionOperation> existing = operationRepository.findById(entry.operationId())
                .filter(operation -> operation.tenantId().equals(tenantId));
        if (existing.isEmpty()) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Existing operation not found")
                    .asRuntimeException());
            return;
        }

        Optional<ItemRecord> item = existing.get().items().stream()
                .filter(candidate -> candidate.itemIndex() == 0)
                .findFirst();
        if (item.isEmpty()) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Existing operation has no item")
                    .asRuntimeException());
            return;
        }
        if (!item.get().state().isTerminal()) {
            responseObserver.onError(Status.FAILED_PRECONDITION
                    .withDescription("Existing operation is not complete")
                    .asRuntimeException());
            return;
        }

        GetResultRequest getResult = GetResultRequest.newBuilder()
                .setTenantId(tenantId)
                .setOperationId(entry.operationId())
                .setItemIndex(0)
                .build();
        Optional<AdapterResult> stored = resultStore.load(tenantId, entry.operationId(), 0);
        SyncExtractionOutcome outcome = stored
                .map(adapterResult -> toOutcome(getResult, item.get(), adapterResult))
                .orElseGet(() -> failedOutcome(getResult, item.get()));
        responseObserver.onNext(OperationMapper.toResult(outcome, item.get()));
        responseObserver.onCompleted();
    }

    @Override
    public void getCapabilities(
            GetCapabilitiesRequest request,
            StreamObserver<ExtractionCapabilities> responseObserver) {
        ExtractionCapabilities.Builder builder = ExtractionCapabilities.newBuilder()
                .setContractVersion("synanton.extraction.v1")
                .setSyncSupported(true)
                .setMaxSyncObjectBytes(properties.getLimits().getMaxSyncObjectBytes())
                .addPayloadSchemaIds("synanton.extraction.document");

        for (ModalityAdapter adapter : extractSyncService.router().productiveAdapters()) {
            for (String mediaType : advertisedTypes(adapter)) {
                builder.addMediaTypes(MediaTypeCapability.newBuilder()
                        .setMediaType(mediaType)
                        .addSupportedFeatures("text")
                        .setMaxObjectBytes(properties.getLimits().getMaxObjectBytes())
                        .build());
            }
        }
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void getCapacity(GetCapacityRequest request, StreamObserver<CapacityResponse> responseObserver) {
        var snapshot = capacityService.getCapacity(request.getTenantId(), request.getMediaType());
        responseObserver.onNext(OperationMapper.toCapacityResponse(snapshot));
        responseObserver.onCompleted();
    }

    @Override
    public void submitExtraction(
            SubmitExtractionRequest request,
            StreamObserver<ExtractionOperation> observer) {
        List<FieldViolation> violations = ExtractionRequestValidator.validate(request);
        if (!violations.isEmpty()) {
            rejectInvalidArgument(observer, violations);
            return;
        }
        submit(RequestMapper.toCommand(request), observer);
    }

    @Override
    public void submitExtractionBatch(
            SubmitExtractionBatchRequest request,
            StreamObserver<ExtractionOperation> observer) {
        List<FieldViolation> violations = ExtractionRequestValidator.validate(request);
        if (!violations.isEmpty()) {
            rejectInvalidArgument(observer, violations);
            return;
        }
        submit(RequestMapper.toCommand(request), observer);
    }

    @Override
    public void getOperations(GetOperationsRequest request, StreamObserver<GetOperationsResponse> observer) {
        List<FieldViolation> violations = ExtractionRequestValidator.validate(request);
        if (!violations.isEmpty()) {
            rejectInvalidArgument(observer, violations);
            return;
        }

        OperationQueryService.QueryResult result = operationQueryService.getOperations(
                request.getTenantId(),
                request.getOperationIdsList());
        observer.onNext(OperationMapper.toGetOperationsResponse(
                result.operations(),
                result.notFoundOperationIds()));
        observer.onCompleted();
    }

    @Override
    public void listCompletedOperations(
            ListCompletedOperationsRequest request,
            StreamObserver<ListCompletedOperationsResponse> observer) {
        List<FieldViolation> violations = ExtractionRequestValidator.validate(request);
        if (!violations.isEmpty()) {
            rejectInvalidArgument(observer, violations);
            return;
        }

        OperationQueryService.CompletedPage page = operationQueryService.listCompleted(
                request.getTenantId(),
                request.getCursor(),
                request.getPageSize());
        observer.onNext(OperationMapper.toListCompletedResponse(page.operations(), page.nextCursor()));
        observer.onCompleted();
    }

    @Override
    public void getResult(GetResultRequest request, StreamObserver<ExtractionResult> observer) {
        Optional<OperationRepository.ExtractionOperation> operation = operationRepository.findById(request.getOperationId())
                .filter(op -> op.tenantId().equals(request.getTenantId()));
        if (operation.isEmpty()) {
            observer.onError(Status.NOT_FOUND.withDescription("Operation not found").asRuntimeException());
            return;
        }

        Optional<ItemRecord> item = operation.get().items().stream()
                .filter(candidate -> candidate.itemIndex() == request.getItemIndex())
                .findFirst();
        if (item.isEmpty()) {
            observer.onError(Status.NOT_FOUND.withDescription("Item not found").asRuntimeException());
            return;
        }

        if (!item.get().state().isTerminal()) {
            observer.onError(Status.FAILED_PRECONDITION
                    .withDescription("Item is not in a terminal state")
                    .asRuntimeException());
            return;
        }

        Optional<AdapterResult> stored = resultStore.load(
                request.getTenantId(),
                request.getOperationId(),
                request.getItemIndex());
        SyncExtractionOutcome outcome = stored
                .map(adapterResult -> toOutcome(request, item.get(), adapterResult))
                .orElseGet(() -> failedOutcome(request, item.get()));
        observer.onNext(OperationMapper.toResult(outcome, item.get()));
        observer.onCompleted();
    }

    @Override
    public void cancelOperation(
            CancelOperationRequest request,
            StreamObserver<ExtractionOperation> observer) {
        Optional<OperationRepository.ExtractionOperation> cancelled = cancelOperationService.cancel(
                request.getTenantId(),
                request.getOperationId());
        if (cancelled.isEmpty()) {
            observer.onError(Status.NOT_FOUND.withDescription("Operation not found").asRuntimeException());
            return;
        }
        observer.onNext(OperationMapper.toProto(cancelled.get()));
        observer.onCompleted();
    }

    @Override
    public void estimateExtraction(SubmitExtractionRequest request, StreamObserver<ExtractionEstimate> observer) {
        List<FieldViolation> violations = ExtractionRequestValidator.validate(request);
        if (!violations.isEmpty()) {
            rejectInvalidArgument(observer, violations);
            return;
        }
        var estimate = capacityService.estimate(RequestMapper.toCommand(request));
        observer.onNext(OperationMapper.toEstimate(estimate));
        observer.onCompleted();
    }

    private void submit(
            SubmitExtractionCommand command,
            StreamObserver<ExtractionOperation> observer) {
        try {
            OperationRepository.ExtractionOperation operation = admissionExecutor.admit(command);
            observer.onNext(OperationMapper.toProto(operation));
            observer.onCompleted();
        } catch (SubmitExtractionService.IdempotencyConflictException e) {
            observer.onError(Status.INVALID_ARGUMENT
                    .withDescription("Idempotency key reused with different request semantics")
                    .asRuntimeException());
        } catch (SubmitExtractionService.CapacityRejectedException e) {
            observer.onError(Status.RESOURCE_EXHAUSTED
                    .withDescription("Tenant capacity saturated")
                    .asRuntimeException());
        } catch (IdempotencyStoreUnavailableException e) {
            observer.onError(Status.UNAVAILABLE
                    .withDescription("Idempotency store unavailable")
                    .asRuntimeException());
        }
    }

    private static SyncExtractionOutcome toOutcome(
            GetResultRequest request,
            ItemRecord item,
            AdapterResult adapterResult) {
        SyncExtractionOutcome.OutcomeStatus status = switch (item.state()) {
            case COMPLETED -> SyncExtractionOutcome.OutcomeStatus.COMPLETED;
            case PARTIAL -> SyncExtractionOutcome.OutcomeStatus.PARTIAL;
            case EXPIRED -> SyncExtractionOutcome.OutcomeStatus.EXPIRED;
            default -> SyncExtractionOutcome.OutcomeStatus.FAILED;
        };
        return new SyncExtractionOutcome(
                request.getOperationId(),
                item.contentRefId(),
                status,
                adapterResult.document(),
                adapterResult.featureStates(),
                adapterResult.failure(),
                null,
                "");
    }

    private static SyncExtractionOutcome failedOutcome(GetResultRequest request, ItemRecord item) {
        return new SyncExtractionOutcome(
                request.getOperationId(),
                item.contentRefId(),
                SyncExtractionOutcome.OutcomeStatus.FAILED,
                null,
                item.featureStates(),
                ExtractionFailure.internalError("Result not found"),
                null,
                "");
    }

    private static void rejectInvalidArgument(StreamObserver<?> observer, List<FieldViolation> violations) {
        String detail = violations.stream()
                .map(FieldViolation::toString)
                .collect(Collectors.joining("; "));
        observer.onError(Status.INVALID_ARGUMENT.withDescription(detail).asRuntimeException());
    }

    private static List<String> advertisedTypes(ModalityAdapter adapter) {
        String id = adapter.processorId();
        if (id != null && id.contains("pdf")) {
            return List.of("application/pdf");
        }
        return List.of(
                "text/plain",
                "text/markdown",
                "text/html",
                "text/csv",
                "application/epub+zip");
    }
}
