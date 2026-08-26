package synanton.extraction.adapter.in.grpc;

import com.google.protobuf.Timestamp;
import synanton.extraction.domain.model.SyncExtractionOutcome;
import synanton.extraction.domain.service.CapacityService;
import synanton.extraction.domain.service.EstimateExtractionService.Estimate;
import synanton.extraction.spi.model.ExtractionFailure;
import synanton.extraction.spi.model.FeatureOutcome;
import synanton.extraction.spi.port.OperationRepository.ItemRecord;
import synanton.extraction.v1.AdmissionVerdict;
import synanton.extraction.v1.CapacityLevel;
import synanton.extraction.v1.CapacityResponse;
import synanton.extraction.v1.ExtractionError;
import synanton.extraction.v1.ExtractionErrorCatalogue;
import synanton.extraction.v1.ExtractionErrorCode;
import synanton.extraction.v1.ExtractionEstimate;
import synanton.extraction.v1.ExtractionItemStatus;
import synanton.extraction.v1.ExtractionOperation;
import synanton.extraction.v1.ExtractionResult;
import synanton.extraction.v1.ExtractionStatus;
import synanton.extraction.v1.FeatureState;
import synanton.extraction.v1.GetOperationsResponse;
import synanton.extraction.v1.ListCompletedOperationsResponse;

import java.time.Instant;
import java.util.List;

final class OperationMapper {

    private OperationMapper() {
    }

    static ExtractionOperation toProto(
            synanton.extraction.spi.port.OperationRepository.ExtractionOperation domain) {
        ExtractionOperation.Builder builder = ExtractionOperation.newBuilder()
                .setOperationId(domain.id())
                .setTenantId(domain.tenantId())
                .setStatus(toStatus(domain.state()))
                .setProgress(deriveProgress(domain))
                .setCreatedAt(toTimestamp(domain.createdAt()))
                .setUpdatedAt(toTimestamp(domain.updatedAt()))
                .setAdmission(toAdmission(domain.admissionVerdict()));

        if (domain.expiresAt() != null) {
            builder.setExpiresAt(toTimestamp(domain.expiresAt()));
        }

        for (ItemRecord item : domain.items()) {
            builder.addItems(toItemStatus(item));
        }
        return builder.build();
    }

    static GetOperationsResponse toGetOperationsResponse(
            List<synanton.extraction.spi.port.OperationRepository.ExtractionOperation> operations,
            List<String> notFoundOperationIds) {
        GetOperationsResponse.Builder builder = GetOperationsResponse.newBuilder();
        operations.stream().map(OperationMapper::toProto).forEach(builder::addOperations);
        builder.addAllNotFoundOperationIds(notFoundOperationIds);
        return builder.build();
    }

    static ListCompletedOperationsResponse toListCompletedResponse(
            List<synanton.extraction.spi.port.OperationRepository.ExtractionOperation> operations,
            String nextCursor) {
        ListCompletedOperationsResponse.Builder builder = ListCompletedOperationsResponse.newBuilder();
        operations.stream().map(OperationMapper::toProto).forEach(builder::addOperations);
        if (nextCursor != null && !nextCursor.isBlank()) {
            builder.setNextCursor(nextCursor);
        }
        return builder.build();
    }

    static CapacityResponse toCapacityResponse(CapacityService.CapacitySnapshot snapshot) {
        CapacityResponse.Builder builder = CapacityResponse.newBuilder()
                .setLevel(toCapacityLevel(snapshot.level()))
                .setAcceptingWork(snapshot.acceptingWork());
        if (snapshot.estimatedQueueDelaySeconds() != null) {
            builder.setEstimatedQueueDelaySeconds(snapshot.estimatedQueueDelaySeconds());
        }
        return builder.build();
    }

    static ExtractionEstimate toEstimate(Estimate estimate) {
        ExtractionEstimate.Builder builder = ExtractionEstimate.newBuilder()
                .setProcessable(estimate.processable());
        if (estimate.estimatedDurationSeconds() != null) {
            builder.setEstimatedDurationSeconds(estimate.estimatedDurationSeconds());
        }
        if (estimate.estimatedPayloadBytes() != null) {
            builder.setEstimatedPayloadBytes(estimate.estimatedPayloadBytes());
        }
        if (estimate.error() != null) {
            builder.setError(toError(estimate.error()));
        }
        return builder.build();
    }

    static ExtractionResult toResult(SyncExtractionOutcome outcome, ItemRecord item) {
        return ResultMapper.toResult(outcome, item.contentRefId(), item.itemIndex());
    }

    private static ExtractionItemStatus toItemStatus(ItemRecord item) {
        ExtractionItemStatus.Builder builder = ExtractionItemStatus.newBuilder()
                .setItemIndex(item.itemIndex())
                .setContentRefId(item.contentRefId())
                .setStatus(toStatus(item.state()))
                .setProgress(item.state().isTerminal() ? 1.0 : 0.0);
        item.featureStates().forEach((key, value) -> builder.putFeatureStates(key, toFeatureState(value)));
        if (item.errorCode() != null) {
            builder.setError(ExtractionError.newBuilder()
                    .setCode(parseErrorCode(item.errorCode()))
                    .setDiagnostic(nvl(item.errorDiagnostic()))
                    .setRetryable(ExtractionErrorCatalogue.isRetryable(parseErrorCode(item.errorCode())))
                    .build());
        }
        return builder.build();
    }

    private static double deriveProgress(
            synanton.extraction.spi.port.OperationRepository.ExtractionOperation operation) {
        if (operation.items().isEmpty()) {
            return operation.state().isTerminal() ? 1.0 : 0.0;
        }
        return operation.items().stream()
                .mapToDouble(item -> item.state().isTerminal() ? 1.0 : 0.0)
                .average()
                .orElse(0.0);
    }

    private static AdmissionVerdict toAdmission(String verdict) {
        if ("ADMISSION_REJECTED".equals(verdict)) {
            return AdmissionVerdict.ADMISSION_REJECTED;
        }
        return AdmissionVerdict.ADMISSION_ADMITTED;
    }

    private static ExtractionStatus toStatus(synanton.extraction.spi.model.OperationState state) {
        return switch (state) {
            case ACCEPTED -> ExtractionStatus.STATUS_ACCEPTED;
            case QUEUED -> ExtractionStatus.STATUS_QUEUED;
            case RUNNING -> ExtractionStatus.STATUS_RUNNING;
            case COMPLETED -> ExtractionStatus.STATUS_COMPLETED;
            case PARTIAL -> ExtractionStatus.STATUS_PARTIAL;
            case FAILED -> ExtractionStatus.STATUS_FAILED;
            case CANCELLED -> ExtractionStatus.STATUS_CANCELLED;
            case EXPIRED -> ExtractionStatus.STATUS_EXPIRED;
        };
    }

    private static CapacityLevel toCapacityLevel(CapacityService.Level level) {
        return switch (level) {
            case AVAILABLE -> CapacityLevel.CAPACITY_AVAILABLE;
            case LIMITED -> CapacityLevel.CAPACITY_LIMITED;
            case SATURATED -> CapacityLevel.CAPACITY_SATURATED;
        };
    }

    private static FeatureState toFeatureState(FeatureOutcome outcome) {
        return switch (outcome) {
            case APPLIED -> FeatureState.FEATURE_APPLIED;
            case NOT_REQUESTED -> FeatureState.FEATURE_NOT_REQUESTED;
            case NOT_APPLICABLE -> FeatureState.FEATURE_NOT_APPLICABLE;
            case UNSUPPORTED -> FeatureState.FEATURE_UNSUPPORTED;
            case FAILED -> FeatureState.FEATURE_FAILED;
            case PARTIAL -> FeatureState.FEATURE_PARTIAL;
        };
    }

    private static ExtractionError toError(ExtractionFailure failure) {
        ExtractionErrorCode code = parseErrorCode(failure.errorCode());
        return ExtractionError.newBuilder()
                .setCode(code)
                .setDiagnostic(nvl(failure.diagnostic()))
                .setRetryable(ExtractionErrorCatalogue.isRetryable(code))
                .build();
    }

    private static ExtractionErrorCode parseErrorCode(String code) {
        try {
            return ExtractionErrorCode.valueOf(code);
        } catch (IllegalArgumentException e) {
            return ExtractionErrorCode.ERROR_INTERNAL_ERROR;
        }
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }
}
