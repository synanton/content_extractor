package synanton.extraction.domain.service;

import synanton.extraction.config.ExtractionGatewayProperties;
import synanton.extraction.domain.model.SyncExtractionOutcome;
import synanton.extraction.domain.model.SyncExtractionOutcome.OutcomeStatus;
import synanton.extraction.domain.port.ExtractionMetricsPort;
import synanton.extraction.spi.model.AdapterResult;
import synanton.extraction.spi.model.ExtractionFailure;
import synanton.extraction.spi.model.ExtractionOptions;
import synanton.extraction.spi.model.ExtractionRequest;
import synanton.extraction.spi.model.ObjectRef;
import synanton.extraction.spi.port.ModalityAdapter;
import synanton.extraction.spi.port.SourceObjectReader;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sync extract path: validate size, read source, route, adapt, time-box the adapter.
 * Same domain path that async submit will await in SCEP-4.
 */
public class ExtractSyncService {

    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();

    private final ExtractionRouter router;
    private final SourceObjectReader sourceReader;
    private final ExtractionGatewayProperties properties;
    private final ExtractionMetricsPort metrics;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public ExtractSyncService(
            ExtractionRouter router,
            SourceObjectReader sourceReader,
            ExtractionGatewayProperties properties) {
        this(router, sourceReader, properties, ExtractionMetricsPort.NOOP);
    }

    public ExtractSyncService(
            ExtractionRouter router,
            SourceObjectReader sourceReader,
            ExtractionGatewayProperties properties,
            ExtractionMetricsPort metrics) {
        this.router = router;
        this.sourceReader = sourceReader;
        this.properties = properties;
        this.metrics = metrics != null ? metrics : ExtractionMetricsPort.NOOP;
    }

    public SyncExtractionOutcome extract(
            String tenantId,
            String idempotencyKey,
            String contentRefId,
            ObjectRef source,
            String mediaType,
            ExtractionOptions options,
            Instant expiresAt) {
        return extract(
                UUID.randomUUID().toString(),
                tenantId,
                idempotencyKey,
                contentRefId,
                source,
                mediaType,
                options,
                expiresAt);
    }

    public SyncExtractionOutcome extract(
            String operationId,
            String tenantId,
            String idempotencyKey,
            String contentRefId,
            ObjectRef source,
            String mediaType,
            ExtractionOptions options,
            Instant expiresAt) {

        long startedAt = System.nanoTime();
        long cpuStart = currentCpuNanos();
        metrics.recordRequest(mediaType);

        if (expiresAt != null && Instant.now().isAfter(expiresAt)) {
            return failed(operationId, contentRefId, OutcomeStatus.EXPIRED,
                    ExtractionFailure.expired("expires_at is in the past"), source.sha256(),
                    mediaType, startedAt, cpuStart, source.sizeBytes());
        }

        long declaredSize = source.sizeBytes();
        long maxSync = properties.getLimits().getMaxSyncObjectBytes();
        long maxObject = properties.getLimits().getMaxObjectBytes();
        if (declaredSize > maxSync || declaredSize > maxObject) {
            return failed(operationId, contentRefId, OutcomeStatus.FAILED,
                    ExtractionFailure.invalidObjectReference(
                            "Object exceeds sync size ceiling"), source.sha256(),
                    mediaType, startedAt, cpuStart, declaredSize);
        }

        Optional<ModalityAdapter> routed = router.route(mediaType);
        if (routed.isEmpty()) {
            return failed(operationId, contentRefId, OutcomeStatus.FAILED,
                    ExtractionFailure.unsupportedMediaType(mediaType), source.sha256(),
                    mediaType, startedAt, cpuStart, declaredSize);
        }
        ModalityAdapter adapter = routed.get();

        long storedSize;
        try {
            storedSize = sourceReader.contentLength(source);
        } catch (IOException e) {
            return failed(operationId, contentRefId, OutcomeStatus.FAILED,
                    ExtractionFailure.objectNotFound(source.bucket() + "/" + source.key()),
                    source.sha256(), mediaType, startedAt, cpuStart, declaredSize);
        }
        if (storedSize > maxSync || storedSize > maxObject) {
            return failed(operationId, contentRefId, OutcomeStatus.FAILED,
                    ExtractionFailure.invalidObjectReference(
                            "Stored object exceeds size ceiling"), source.sha256(),
                    mediaType, startedAt, cpuStart, storedSize);
        }

        byte[] bytes;
        try (InputStream in = sourceReader.read(source)) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            return failed(operationId, contentRefId, OutcomeStatus.FAILED,
                    ExtractionFailure.objectNotFound(source.bucket() + "/" + source.key()),
                    source.sha256(), mediaType, startedAt, cpuStart, declaredSize);
        }

        String actualSha = sha256Hex(bytes);
        if (source.sha256() != null && !source.sha256().isBlank()
                && !source.sha256().equalsIgnoreCase(actualSha)) {
            return failed(operationId, contentRefId, OutcomeStatus.FAILED,
                    ExtractionFailure.objectChanged("sha256 mismatch"), source.sha256(),
                    mediaType, startedAt, cpuStart, bytes.length);
        }

        ExtractionRequest request = new ExtractionRequest(
                operationId,
                tenantId,
                idempotencyKey,
                contentRefId,
                source,
                mediaType,
                options != null ? options : ExtractionOptions.defaults(),
                "PRIORITY_NORMAL",
                expiresAt);

        long timeoutSeconds = properties.getLimits().getMaxDurationSeconds();
        AdapterResult result;
        try {
            Future<AdapterResult> future = workers.submit(
                    () -> adapter.extract(request, new java.io.ByteArrayInputStream(bytes)));
            result = future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return failed(operationId, contentRefId, OutcomeStatus.FAILED,
                    ExtractionFailure.timeout("Adapter exceeded max duration"), actualSha,
                    mediaType, startedAt, cpuStart, bytes.length);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(operationId, contentRefId, OutcomeStatus.FAILED,
                    ExtractionFailure.internalError("Interrupted"), actualSha,
                    mediaType, startedAt, cpuStart, bytes.length);
        } catch (ExecutionException e) {
            return failed(operationId, contentRefId, OutcomeStatus.FAILED,
                    ExtractionFailure.extractionFailed(e.getCause() != null
                            ? e.getCause().getMessage() : e.getMessage()), actualSha,
                    mediaType, startedAt, cpuStart, bytes.length);
        }

        if (!result.isSuccess()) {
            return failed(operationId, contentRefId, OutcomeStatus.FAILED,
                    result.failure(), actualSha, mediaType, startedAt, cpuStart, bytes.length);
        }

        long payloadBytes = result.document().flattenedText() == null
                ? 0
                : result.document().flattenedText().getBytes(StandardCharsets.UTF_8).length;
        if (payloadBytes > properties.getLimits().getMaxPayloadBytes()) {
            return failed(operationId, contentRefId, OutcomeStatus.FAILED,
                    ExtractionFailure.invalidObjectReference("Payload exceeds output ceiling"),
                    actualSha, mediaType, startedAt, cpuStart, bytes.length);
        }

        long wallMs = (System.nanoTime() - startedAt) / 1_000_000L;
        long cpuNs = currentCpuNanos() - cpuStart;
        long outputChars = result.document().flattenedText() == null
                ? 0
                : result.document().flattenedText().length();
        metrics.recordCompleted(mediaType, wallMs, payloadBytes);

        return new SyncExtractionOutcome(
                operationId,
                contentRefId,
                OutcomeStatus.COMPLETED,
                result.document(),
                result.featureStates(),
                null,
                opaqueProcessorId(adapter.processorId()),
                actualSha,
                wallMs,
                cpuNs,
                bytes.length,
                outputChars);
    }

    public ExtractionRouter router() {
        return router;
    }

    private SyncExtractionOutcome failed(
            String operationId,
            String contentRefId,
            OutcomeStatus status,
            ExtractionFailure failure,
            String sha,
            String mediaType,
            long startedAtNanos,
            long cpuStartNanos,
            long inputBytes) {
        if (status == OutcomeStatus.EXPIRED) {
            metrics.recordFailed(mediaType, "ERROR_EXPIRED");
        } else if (failure != null) {
            metrics.recordFailed(mediaType, failure.errorCode());
        }
        long wallMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
        long cpuNs = currentCpuNanos() - cpuStartNanos;
        return new SyncExtractionOutcome(
                operationId, contentRefId, status, null, Map.of(), failure, null, sha,
                wallMs, cpuNs, inputBytes, 0);
    }

    private static long currentCpuNanos() {
        return THREAD_MX.isCurrentThreadCpuTimeSupported()
            ? THREAD_MX.getCurrentThreadCpuTime() : 0L;
    }

    static String opaqueProcessorId(String raw) {
        if (raw == null) {
            return "document-adapter";
        }
        if (raw.contains("pdf")) {
            return "pdf-adapter";
        }
        if (raw.contains("text")) {
            return "text-adapter";
        }
        return "document-adapter";
    }

    static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
