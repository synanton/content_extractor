package synanton.extraction.domain.model;

import synanton.extraction.spi.model.ExtractionFailure;
import synanton.extraction.spi.model.FeatureOutcome;
import synanton.extraction.spi.model.NormalizedDocument;

import java.util.Map;

/**
 * Domain outcome of a synchronous extraction. Protobuf mapping happens in the gRPC adapter.
 */
public record SyncExtractionOutcome(
        String operationId,
        String contentRefId,
        OutcomeStatus status,
        NormalizedDocument document,
        Map<String, FeatureOutcome> featureStates,
        ExtractionFailure failure,
        String processorId,
        String sourceSha256) {

    public enum OutcomeStatus {
        COMPLETED,
        PARTIAL,
        FAILED,
        EXPIRED
    }

    public SyncExtractionOutcome {
        featureStates = featureStates == null ? Map.of() : Map.copyOf(featureStates);
    }
}
