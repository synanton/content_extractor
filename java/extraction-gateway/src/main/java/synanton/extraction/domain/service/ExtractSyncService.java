package synanton.extraction.domain.service;

import synanton.extraction.config.ExtractionGatewayProperties;
import synanton.extraction.domain.model.SyncExtractionOutcome;
import synanton.extraction.domain.model.SyncExtractionOutcome.OutcomeStatus;
import synanton.extraction.spi.model.AdapterResult;
import synanton.extraction.spi.model.ExtractionFailure;
import synanton.extraction.spi.model.ExtractionOptions;
import synanton.extraction.spi.model.ExtractionRequest;
import synanton.extraction.spi.model.ObjectRef;
import synanton.extraction.spi.port.ModalityAdapter;
import synanton.extraction.spi.port.SourceObjectReader;

import java.io.IOException;
import java.io.InputStream;
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

    private final ExtractionRouter router;
    private final SourceObjectReader sourceReader;
    private final ExtractionGatewayProperties properties;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public ExtractSyncService(
            ExtractionRouter router,
            SourceObjectReader sourceReader,
            ExtractionGatewayProperties properties) {
        this.router = router;
        this.sourceReader = sourceReader;
        this.properties = properties;
    }

    public SyncExtractionOutcome extract(
            String tenantId,
            String idempotencyKey,
            String contentRefId,
            ObjectRef source,
            String mediaType,
            ExtractionOptions options,
            Instant expiresAt) {

        String operationId = UUID.randomUUID().toString();

        if (expiresAt != null && Instant.now().isAfter(expiresAt)) {
            return failed(operationId, contentRefId, OutcomeStatus.EXPIRED,
                    ExtractionFailure.expired("expires_at is in the past"), source.sha256());
        }

        long declaredSize = source.sizeBytes();
        long maxSync = properties.getLimits().getMaxSyncObjectBytes();
        long maxObject = properties.getLimits().getMaxObjectBytes();
        if (declaredSize > maxSync || declaredSize > maxObject) {
            return failed(operationId, contentRefId, OutcomeStatus.FAILED,
                    ExtractionFailure.invalidObjectReference(
                            "Object exceeds sync size ceiling"), source.sha256());
        }

        Optional<ModalityAdapter> routed = router.route(mediaType);
        if (routed.isEmpty()) {
            return failed(operationId, contentRefId, OutcomeStatus.FAILED,
                    ExtractionFailure.unsupportedMediaType(mediaType), source.sha256());
        }
        ModalityAdapter adapter = routed.get();

        long storedSize;
        try {
            storedSize = sourceReader.contentLength(source);
        } catch (IOException e) {
            return failed(operationId, contentRefId, OutcomeStatus.FAILED,
                    ExtractionFailure.objectNotFound(source.bucket() + "/" + source.key()),
                    source.sha256());
        }
        if (storedSize > maxSync || storedSize > maxObject) {
            return failed(operationId, contentRefId, OutcomeStatus.FAILED,
                    ExtractionFailure.invalidObjectReference(
                            "Stored object exceeds size ceiling"), source.sha256());
        }

        byte[] bytes;
        try (InputStream in = sourceReader.read(source)) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            return failed(operationId, contentRefId, OutcomeStatus.FAILED,
                    ExtractionFailure.objectNotFound(source.bucket() + "/" + source.key()),
                    source.sha256());
        }

        String actualSha = sha256Hex(bytes);
        if (source.sha256() != null && !source.sha256().isBlank()
                && !source.sha256().equalsIgnoreCase(actualSha)) {
            return failed(operationId, contentRefId, OutcomeStatus.FAILED,
                    ExtractionFailure.objectChanged("sha256 mismatch"), source.sha256());
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
                    ExtractionFailure.timeout("Adapter exceeded max duration"), actualSha);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(operationId, contentRefId, OutcomeStatus.FAILED,
                    ExtractionFailure.internalError("Interrupted"), actualSha);
        } catch (ExecutionException e) {
            return failed(operationId, contentRefId, OutcomeStatus.FAILED,
                    ExtractionFailure.extractionFailed(e.getCause() != null
                            ? e.getCause().getMessage() : e.getMessage()), actualSha);
        }

        if (!result.isSuccess()) {
            return failed(operationId, contentRefId, OutcomeStatus.FAILED,
                    result.failure(), actualSha);
        }

        long payloadBytes = result.document().flattenedText() == null
                ? 0
                : result.document().flattenedText().getBytes(StandardCharsets.UTF_8).length;
        if (payloadBytes > properties.getLimits().getMaxPayloadBytes()) {
            return failed(operationId, contentRefId, OutcomeStatus.FAILED,
                    ExtractionFailure.invalidObjectReference("Payload exceeds output ceiling"),
                    actualSha);
        }

        return new SyncExtractionOutcome(
                operationId,
                contentRefId,
                OutcomeStatus.COMPLETED,
                result.document(),
                result.featureStates(),
                null,
                opaqueProcessorId(adapter.processorId()),
                actualSha);
    }

    public ExtractionRouter router() {
        return router;
    }

    private static SyncExtractionOutcome failed(
            String operationId,
            String contentRefId,
            OutcomeStatus status,
            ExtractionFailure failure,
            String sha) {
        return new SyncExtractionOutcome(
                operationId, contentRefId, status, null, Map.of(), failure, null, sha);
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
