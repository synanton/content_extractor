package synanton.extraction.adapter.in.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;
import synanton.extraction.config.ExtractionGatewayProperties;
import synanton.extraction.domain.model.SyncExtractionOutcome;
import synanton.extraction.domain.service.ExtractSyncService;
import synanton.extraction.spi.port.ModalityAdapter;
import synanton.extraction.v1.CancelOperationRequest;
import synanton.extraction.v1.CapacityLevel;
import synanton.extraction.v1.CapacityResponse;
import synanton.extraction.v1.ExtractionCapabilities;
import synanton.extraction.v1.ExtractionEstimate;
import synanton.extraction.v1.ExtractionOperation;
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
import java.util.stream.Collectors;

@Component
public class ExtractionGrpcAdapter extends ExtractionServiceGrpc.ExtractionServiceImplBase {

    private final ExtractSyncService extractSyncService;
    private final ExtractionGatewayProperties properties;

    public ExtractionGrpcAdapter(
            ExtractSyncService extractSyncService,
            ExtractionGatewayProperties properties) {
        this.extractSyncService = extractSyncService;
        this.properties = properties;
    }

    @Override
    public void extractSync(
            SubmitExtractionRequest request,
            StreamObserver<ExtractionResult> responseObserver) {
        List<FieldViolation> violations = ExtractionRequestValidator.validate(request);
        if (!violations.isEmpty()) {
            String detail = violations.stream()
                    .map(FieldViolation::toString)
                    .collect(Collectors.joining("; "));
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(detail).asRuntimeException());
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
        responseObserver.onNext(CapacityResponse.newBuilder()
                .setLevel(CapacityLevel.CAPACITY_AVAILABLE)
                .setAcceptingWork(true)
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void submitExtraction(SubmitExtractionRequest request, StreamObserver<ExtractionOperation> o) {
        unimplemented(o);
    }

    @Override
    public void submitExtractionBatch(SubmitExtractionBatchRequest request, StreamObserver<ExtractionOperation> o) {
        unimplemented(o);
    }

    @Override
    public void getOperations(GetOperationsRequest request, StreamObserver<GetOperationsResponse> o) {
        unimplemented(o);
    }

    @Override
    public void listCompletedOperations(
            ListCompletedOperationsRequest request,
            StreamObserver<ListCompletedOperationsResponse> o) {
        unimplemented(o);
    }

    @Override
    public void getResult(GetResultRequest request, StreamObserver<ExtractionResult> o) {
        unimplemented(o);
    }

    @Override
    public void cancelOperation(CancelOperationRequest request, StreamObserver<ExtractionOperation> o) {
        unimplemented(o);
    }

    @Override
    public void estimateExtraction(SubmitExtractionRequest request, StreamObserver<ExtractionEstimate> o) {
        unimplemented(o);
    }

    private static void unimplemented(StreamObserver<?> observer) {
        observer.onError(Status.UNIMPLEMENTED
                .withDescription("Async extraction is not enabled in this PoC")
                .asRuntimeException());
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
