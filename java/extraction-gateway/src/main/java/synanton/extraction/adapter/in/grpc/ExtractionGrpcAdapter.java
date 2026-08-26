package synanton.extraction.adapter.in.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;
import synanton.extraction.adapter.out.persistence.JdbcIdempotencyStore.IdempotencyStoreUnavailableException;
import synanton.extraction.adapter.out.persistence.OperationAdmissionExecutor;
import synanton.extraction.config.ExtractionGatewayProperties;
import synanton.extraction.domain.model.SubmitExtractionCommand;
import synanton.extraction.domain.model.SyncExtractionOutcome;
import synanton.extraction.domain.service.CancelOperationService;
import synanton.extraction.domain.service.CapacityService;
import synanton.extraction.domain.service.ExtractSyncService;
import synanton.extraction.domain.service.OperationQueryService;
import synanton.extraction.domain.service.SubmitExtractionService;
import synanton.extraction.spi.model.AdapterResult;
import synanton.extraction.spi.model.ObjectRef;
import synanton.extraction.spi.port.ModalityAdapter;
import synanton.extraction.spi.port.OperationRepository;
import synanton.extraction.spi.port.OperationRepository.ItemRecord;
import synanton.extraction.spi.port.ResultStore;
import synanton.extraction.v1.CancelOperationRequest;
import synanton.extraction.v1.CapacityResponse;
import synanton.extraction.v1.ExtractionCapabilities;
import synanton.extraction.v1.ExtractionEstimate;
import synanton.extraction.v1.ExtractionResult;
import synanton.extraction.v1.ExtractionServiceGrpc;
import synanton.extraction.v1.GetCapacityRequest;
import synanton.extraction.v1.GetCapabilitiesRequest;
import synanton.extraction.v1.GetOperationsRequest;
import synanton.extraction.v1.GetOperationsResponse;
import synanton.extraction.v1.GetResultRequest;
import synanton.extraction.v1.ListCompletedOperationsRequest;
import synanton.extraction.v1.ListCompletedOperationsResponse;
import synanton.extraction.v1.MediaTypeCapability;
import synanton.extraction.v1.SubmitExtractionBatchRequest;
import synanton.extraction.v1.SubmitExtractionRequest;
import synanton.extraction.v1.validation.ExtractionRequestValidator;
import synanton.extraction.v1.validation.FieldViolation;

import java.util.List;
import java.util.Optional;
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
    private final ExtractionGatewayProperties properties;

    public ExtractionGrpcAdapter(
            ExtractSyncService extractSyncService,
            OperationAdmissionExecutor admissionExecutor,
            OperationQueryService operationQueryService,
            CancelOperationService cancelOperationService,
            CapacityService capacityService,
            OperationRepository operationRepository,
            ResultStore resultStore,
            ExtractionGatewayProperties properties) {
        this.extractSyncService = extractSyncService;
        this.admissionExecutor = admissionExecutor;
        this.operationQueryService = operationQueryService;
        this.cancelOperationService = cancelOperationService;
        this.capacityService = capacityService;
        this.operationRepository = operationRepository;
        this.resultStore = resultStore;
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

        var item = request.getItem();
        SyncExtractionOutcome outcome = extractSyncService.extract(
                request.getTenantId(),
                request.getIdempotencyKey(),
                item.getContentRefId(),
                ResultMapper.toObjectRef(item.getSource()),
                item.getMediaType(),
                ResultMapper.toOptions(item.getOptions()),
                ResultMapper.expiresAt(request));
        responseObserver.onNext(ResultMapper.toResult(outcome, request));
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
            StreamObserver<synanton.extraction.v1.ExtractionOperation> observer) {
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
            StreamObserver<synanton.extraction.v1.ExtractionOperation> observer) {
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
            StreamObserver<synanton.extraction.v1.ExtractionOperation> observer) {
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
            StreamObserver<synanton.extraction.v1.ExtractionOperation> observer) {
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
                synanton.extraction.spi.model.ExtractionFailure.internalError("Result not found"),
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
