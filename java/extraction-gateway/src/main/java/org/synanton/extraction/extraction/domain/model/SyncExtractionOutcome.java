package org.synanton.extraction.extraction.domain.model;

import org.synanton.extraction.spi.spi.model.ExtractionFailure;
import org.synanton.extraction.spi.spi.model.FeatureOutcome;
import org.synanton.extraction.spi.spi.model.NormalizedDocument;

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
        String sourceSha256,
        long wallMs,
        long cpuNs,
        long inputBytes,
        long outputChars) {

    public enum OutcomeStatus {
        COMPLETED,
        PARTIAL,
        FAILED,
        EXPIRED
    }

    public SyncExtractionOutcome {
        featureStates = featureStates == null ? Map.of() : Map.copyOf(featureStates);
    }

    public SyncExtractionOutcome(
            String operationId,
            String contentRefId,
            OutcomeStatus status,
            NormalizedDocument document,
            Map<String, FeatureOutcome> featureStates,
            ExtractionFailure failure,
            String processorId,
            String sourceSha256) {
        this(operationId, contentRefId, status, document, featureStates, failure,
            processorId, sourceSha256, 0, 0, 0, 0);
    }
}
