package synanton.extraction.adapter.out.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import synanton.extraction.domain.port.ExtractionMetricsPort;

import java.util.concurrent.TimeUnit;

@Component
public class MicrometerExtractionMetrics implements ExtractionMetricsPort {

    private final MeterRegistry registry;

    public MicrometerExtractionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordRequest(String mediaType) {
        Counter.builder("extraction_requests_total")
                .tag("media_type", safeTag(mediaType))
                .register(registry)
                .increment();
    }

    @Override
    public void recordCompleted(String mediaType, long durationMs, long payloadBytes) {
        Counter.builder("extraction_completed_total")
                .tag("media_type", safeTag(mediaType))
                .register(registry)
                .increment();
        Timer.builder("extraction_duration_seconds")
                .tag("media_type", safeTag(mediaType))
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
        if (payloadBytes > 0) {
            Counter.builder("extraction_payload_bytes")
                    .tag("media_type", safeTag(mediaType))
                    .register(registry)
                    .increment(payloadBytes);
        }
    }

    @Override
    public void recordFailed(String mediaType, String errorCode) {
        Counter.builder("extraction_failed_total")
                .tag("media_type", safeTag(mediaType))
                .tag("error_code", safeTag(errorCode))
                .register(registry)
                .increment();
    }

    private static String safeTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.length() > 64 ? value.substring(0, 64) : value;
    }
}
