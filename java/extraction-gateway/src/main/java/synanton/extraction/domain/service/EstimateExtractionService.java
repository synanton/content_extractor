package synanton.extraction.domain.service;

import synanton.extraction.spi.model.ExtractionFailure;

public class EstimateExtractionService {

    public record Estimate(
            boolean processable,
            Long estimatedDurationSeconds,
            Long estimatedPayloadBytes,
            ExtractionFailure error) {

        static Estimate processable(long durationSeconds, long payloadBytes) {
            return new Estimate(true, durationSeconds, payloadBytes, null);
        }

        static Estimate unsupported(String mediaType) {
            return new Estimate(
                    false,
                    null,
                    null,
                    ExtractionFailure.unsupportedMediaType(mediaType));
        }

        static Estimate invalid(String diagnostic) {
            return new Estimate(false, null, null, ExtractionFailure.invalidRequest(diagnostic));
        }
    }
}
