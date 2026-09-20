package org.synanton.extraction.domain.service;

import org.junit.jupiter.api.Test;
import org.synanton.extraction.spi.model.FeatureOutcome;
import org.synanton.extraction.adapter.document.text.TextModalityAdapter;
import org.synanton.extraction.adapter.out.objectstore.InMemorySourceObjectReader;
import org.synanton.extraction.config.ExtractionGatewayProperties;
import org.synanton.extraction.domain.model.SyncExtractionOutcome;
import org.synanton.extraction.spi.model.AdapterResult;
import org.synanton.extraction.spi.model.ExtractionFailure;
import org.synanton.extraction.spi.model.ExtractionOptions;
import org.synanton.extraction.spi.model.ExtractionRequest;
import org.synanton.extraction.spi.model.ObjectRef;
import org.synanton.extraction.spi.port.ModalityAdapter;
import org.synanton.extraction.spi.port.SourceObjectReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractSyncServiceTest {

    @Test
    void shouldIncludeUsageMetricsOnSuccess() throws Exception {
        byte[] body = "Hello world\n\nSecond paragraph".getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));

        InMemorySourceObjectReader store = new InMemorySourceObjectReader();
        store.put("synanton-hot", "demo/doc1", body);

        ExtractionGatewayProperties props = new ExtractionGatewayProperties();
        ExtractSyncService service = new ExtractSyncService(
                new ExtractionRouter(List.of(new TextModalityAdapter())),
                store,
                props);

        ObjectRef ref = new ObjectRef("synanton-hot", "demo/doc1", "", sha, body.length);
        SyncExtractionOutcome outcome = service.extract(
                "demo", "key-1", "ref-1", ref, "text/plain", ExtractionOptions.defaults(), null);

        assertThat(outcome.status()).isEqualTo(SyncExtractionOutcome.OutcomeStatus.COMPLETED);
        assertThat(outcome.wallMs()).isPositive();
        assertThat(outcome.outputChars()).isPositive();
        assertThat(outcome.inputBytes()).isEqualTo(body.length);
    }

    @Test
    void shouldExtractPlainTextSync() throws Exception {
        byte[] body = "Hello world\n\nSecond paragraph".getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));

        InMemorySourceObjectReader store = new InMemorySourceObjectReader();
        store.put("synanton-hot", "demo/doc1", body);

        ExtractionGatewayProperties props = new ExtractionGatewayProperties();
        ExtractSyncService service = new ExtractSyncService(
                new ExtractionRouter(List.of(new TextModalityAdapter())),
                store,
                props);

        ObjectRef ref = new ObjectRef("synanton-hot", "demo/doc1", "", sha, body.length);
        SyncExtractionOutcome outcome = service.extract(
                "demo", "key-1", "ref-1", ref, "text/plain", ExtractionOptions.defaults(), null);

        assertThat(outcome.status()).isEqualTo(SyncExtractionOutcome.OutcomeStatus.COMPLETED);
        assertThat(outcome.document().flattenedText()).contains("Hello world");
        assertThat(outcome.document().elements()).isNotEmpty();
        assertThat(outcome.featureStates()).containsEntry(
                "text", FeatureOutcome.APPLIED);
        assertThat(outcome.featureStates()).containsEntry(
                "ocr", FeatureOutcome.NOT_APPLICABLE);
    }

    @Test
    void shouldRejectOversizedObjectBeforeRead() throws Exception {
        byte[] body = "tiny".getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        InMemorySourceObjectReader store = new InMemorySourceObjectReader();
        store.put("b", "k", body);

        ExtractionGatewayProperties props = new ExtractionGatewayProperties();
        props.getLimits().setMaxSyncObjectBytes(2);
        ExtractSyncService service = new ExtractSyncService(
                new ExtractionRouter(List.of(new TextModalityAdapter())),
                store,
                props);

        ObjectRef ref = new ObjectRef("b", "k", "", sha, 100);
        SyncExtractionOutcome outcome = service.extract(
                "demo", "key-1", "ref-1", ref, "text/plain", ExtractionOptions.defaults(), null);

        assertThat(outcome.status()).isEqualTo(SyncExtractionOutcome.OutcomeStatus.FAILED);
        assertThat(outcome.failure().errorCode()).isEqualTo("ERROR_INVALID_OBJECT_REFERENCE");
    }

    @Test
    void shouldReturnUnsupportedForUnknownMediaTypeWithoutDownloading() throws Exception {
        byte[] body = "x".getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        TrackingSourceObjectReader store = new TrackingSourceObjectReader(body);

        ExtractSyncService service = new ExtractSyncService(
                new ExtractionRouter(List.of(new TextModalityAdapter())),
                store,
                new ExtractionGatewayProperties());

        ObjectRef ref = new ObjectRef("b", "k", "", sha, body.length);
        SyncExtractionOutcome outcome = service.extract(
                "demo", "key-1", "ref-1", ref, "application/zip", ExtractionOptions.defaults(), null);

        assertThat(outcome.failure().errorCode()).isEqualTo("ERROR_UNSUPPORTED_MEDIA_TYPE");
        assertThat(store.contentLengthCalls.get()).isZero();
        assertThat(store.readCalls.get()).isZero();
    }

    /**
     * Regression test for a runtime-only classpath bug: Spring Boot's dependency-management
     * BOM force-downgraded commons-lang3 to 3.14.0 in this module (the only one the BOM
     * applies to), below the 3.18.0 Tika's parser modules actually request. That version
     * lacks {@code commons-lang3}'s {@code SystemProperties.getUserName(String)} overload,
     * which something in Tika's {@code AutoDetectParser} call chain invokes for realistic
     * multi-paragraph text - throwing a {@code NoSuchMethodError} that, being an {@code Error}
     * rather than an {@code Exception}, is not caught by {@code TextModalityAdapter}'s own
     * catch block and surfaced instead as a silent {@code ERROR_EXTRACTION_FAILED} here.
     * A trivial single-line fixture (as in {@link #shouldExtractPlainTextSync()}) does not
     * exercise the failing code path - this uses a real multi-paragraph demo document that did.
     */
    @Test
    void shouldExtractRealisticMultiParagraphTextWithoutClasspathError() throws Exception {
        byte[] body = Files.readAllBytes(Path.of("src/test/resources/fixtures/supply-chain-overview.txt"));
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));

        InMemorySourceObjectReader store = new InMemorySourceObjectReader();
        store.put("synanton-hot", "demo/supply-chain-overview", body);

        ExtractionGatewayProperties props = new ExtractionGatewayProperties();
        ExtractSyncService service = new ExtractSyncService(
                new ExtractionRouter(List.of(new TextModalityAdapter())),
                store,
                props);

        ObjectRef ref = new ObjectRef("synanton-hot", "demo/supply-chain-overview", "", sha, body.length);
        SyncExtractionOutcome outcome = service.extract(
                "demo", "key-1", "ref-1", ref, "text/plain", ExtractionOptions.defaults(), null);

        assertThat(outcome.status()).isEqualTo(SyncExtractionOutcome.OutcomeStatus.COMPLETED);
        assertThat(outcome.document().flattenedText())
                .contains("Supply Chain Management: Principles and Risk Overview")
                .contains("Acme Corp")
                .contains("Globex Manufacturing");
    }

    @Test
    void shouldReturnTimeoutWhenAdapterExceedsMaxDuration() throws Exception {
        byte[] body = "slow".getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        InMemorySourceObjectReader store = new InMemorySourceObjectReader();
        store.put("b", "k", body);

        ExtractionGatewayProperties props = new ExtractionGatewayProperties();
        props.getLimits().setMaxDurationSeconds(1);

        ExtractSyncService service = new ExtractSyncService(
                new ExtractionRouter(List.of(new SlowModalityAdapter())),
                store,
                props);

        ObjectRef ref = new ObjectRef("b", "k", "", sha, body.length);
        SyncExtractionOutcome outcome = service.extract(
                "demo", "key-1", "ref-1", ref, "text/plain", ExtractionOptions.defaults(), null);

        assertThat(outcome.status()).isEqualTo(SyncExtractionOutcome.OutcomeStatus.FAILED);
        assertThat(outcome.failure().errorCode()).isEqualTo("ERROR_TIMEOUT");
    }

    private static final class TrackingSourceObjectReader implements SourceObjectReader {
        private final byte[] body;
        private final AtomicInteger contentLengthCalls = new AtomicInteger();
        private final AtomicInteger readCalls = new AtomicInteger();

        TrackingSourceObjectReader(byte[] body) {
            this.body = body;
        }

        @Override
        public InputStream read(ObjectRef ref) throws IOException {
            readCalls.incrementAndGet();
            return new java.io.ByteArrayInputStream(body);
        }

        @Override
        public long contentLength(ObjectRef ref) throws IOException {
            contentLengthCalls.incrementAndGet();
            return body.length;
        }
    }

    private static final class SlowModalityAdapter implements ModalityAdapter {
        @Override
        public boolean supports(String mediaType) {
            return "text/plain".equals(mediaType);
        }

        @Override
        public AdapterResult extract(ExtractionRequest request, InputStream source) {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AdapterResult.failed(
                        ExtractionFailure.internalError("Interrupted"),
                        java.util.Map.of());
            }
            return new TextModalityAdapter().extract(request, source);
        }

        @Override
        public String processorId() {
            return "slow-text-adapter";
        }
    }
}
