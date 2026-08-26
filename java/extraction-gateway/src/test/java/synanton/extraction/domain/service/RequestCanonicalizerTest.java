package synanton.extraction.domain.service;

import org.junit.jupiter.api.Test;
import synanton.extraction.domain.model.ExtractionItemCommand;
import synanton.extraction.domain.model.SubmitExtractionCommand;
import synanton.extraction.spi.model.ExtractionOptions;
import synanton.extraction.spi.model.ObjectRef;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestCanonicalizerTest {

    private final RequestCanonicalizer canonicalizer = new RequestCanonicalizer();

    @Test
    void shouldProduceStableHashForSameSubmitSemantics() {
        SubmitExtractionCommand command = sampleCommand("job-1");
        String first = canonicalizer.canonicalize(command);
        String second = canonicalizer.canonicalize(command);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void shouldProduceDifferentHashWhenMediaTypeChanges() {
        SubmitExtractionCommand baseline = sampleCommand("job-2");
        SubmitExtractionCommand changed = new SubmitExtractionCommand(
                baseline.tenantId(),
                baseline.idempotencyKey(),
                List.of(new ExtractionItemCommand(
                        baseline.items().getFirst().contentRefId(),
                        baseline.items().getFirst().source(),
                        "text/html",
                        baseline.items().getFirst().options())),
                baseline.priority(),
                baseline.expiresAt());

        assertThat(canonicalizer.canonicalize(changed))
                .isNotEqualTo(canonicalizer.canonicalize(baseline));
    }

    private static SubmitExtractionCommand sampleCommand(String idempotencyKey) {
        return new SubmitExtractionCommand(
                "tenant-a",
                idempotencyKey,
                List.of(new ExtractionItemCommand(
                        "content-1",
                        new ObjectRef("bucket", "key", "v1", "a".repeat(64), 128),
                        "text/plain",
                        ExtractionOptions.defaults())),
                "PRIORITY_NORMAL",
                Instant.parse("2030-01-01T00:00:00Z"));
    }
}
