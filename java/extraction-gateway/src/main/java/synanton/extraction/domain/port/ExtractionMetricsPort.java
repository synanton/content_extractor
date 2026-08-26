package synanton.extraction.domain.port;

/**
 * Observability hook for sync extraction (SCEP-2 §29 counters).
 */
public interface ExtractionMetricsPort {

    void recordRequest(String mediaType);

    void recordCompleted(String mediaType, long durationMs, long payloadBytes);

    void recordFailed(String mediaType, String errorCode);

    ExtractionMetricsPort NOOP = new ExtractionMetricsPort() {
        @Override
        public void recordRequest(String mediaType) {}

        @Override
        public void recordCompleted(String mediaType, long durationMs, long payloadBytes) {}

        @Override
        public void recordFailed(String mediaType, String errorCode) {}
    };
}
