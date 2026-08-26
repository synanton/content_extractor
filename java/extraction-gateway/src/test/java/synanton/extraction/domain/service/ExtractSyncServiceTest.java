package synanton.extraction.domain.service;

import org.junit.jupiter.api.Test;
import synanton.extraction.adapter.document.text.TextModalityAdapter;
import synanton.extraction.adapter.out.objectstore.InMemorySourceObjectReader;
import synanton.extraction.config.ExtractionGatewayProperties;
import synanton.extraction.domain.model.SyncExtractionOutcome;
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
                "text", synanton.extraction.spi.model.FeatureOutcome.APPLIED);
        assertThat(outcome.featureStates()).containsEntry(
                "ocr", synanton.extraction.spi.model.FeatureOutcome.NOT_APPLICABLE);
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
