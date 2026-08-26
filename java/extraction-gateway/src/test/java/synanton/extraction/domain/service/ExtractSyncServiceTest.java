package synanton.extraction.domain.service;

import org.junit.jupiter.api.Test;
import synanton.extraction.adapter.document.text.TextModalityAdapter;
import synanton.extraction.adapter.out.objectstore.InMemorySourceObjectReader;
import synanton.extraction.config.ExtractionGatewayProperties;
import synanton.extraction.domain.model.SyncExtractionOutcome;
import synanton.extraction.spi.model.ExtractionOptions;
import synanton.extraction.spi.model.ObjectRef;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractSyncServiceTest {

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
    void shouldReturnUnsupportedForUnknownMediaType() throws Exception {
        byte[] body = "x".getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        InMemorySourceObjectReader store = new InMemorySourceObjectReader();
        store.put("b", "k", body);

        ExtractSyncService service = new ExtractSyncService(
                new ExtractionRouter(List.of(new TextModalityAdapter())),
                store,
                new ExtractionGatewayProperties());

        ObjectRef ref = new ObjectRef("b", "k", "", sha, body.length);
        SyncExtractionOutcome outcome = service.extract(
                "demo", "key-1", "ref-1", ref, "application/zip", ExtractionOptions.defaults(), null);

        assertThat(outcome.failure().errorCode()).isEqualTo("ERROR_UNSUPPORTED_MEDIA_TYPE");
    }
}
