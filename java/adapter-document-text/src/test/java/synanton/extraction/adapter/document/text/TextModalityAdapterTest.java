package synanton.extraction.adapter.document.text;

import org.junit.jupiter.api.Test;
import synanton.extraction.spi.model.AdapterResult;
import synanton.extraction.spi.model.ExtractionOptions;
import synanton.extraction.spi.model.ExtractionRequest;
import synanton.extraction.spi.model.FeatureOutcome;
import synanton.extraction.spi.model.ObjectRef;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TextModalityAdapter}. No Spring context is loaded.
 */
class TextModalityAdapterTest {

    private static final TextModalityAdapter ADAPTER = new TextModalityAdapter();

    private static ExtractionRequest requestFor(String mediaType) {
        return new ExtractionRequest(
                "op-1",
                "tenant1",
                "key1",
                "ref1",
                new ObjectRef("bucket", "key", "", "a".repeat(64), 1L),
                mediaType,
                ExtractionOptions.defaults(),
                "PRIORITY_NORMAL",
                null
        );
    }

    private static ExtractionRequest requestFor(String mediaType, ExtractionOptions options) {
        return new ExtractionRequest(
                "op-1",
                "tenant1",
                "key1",
                "ref1",
                new ObjectRef("bucket", "key", "", "a".repeat(64), 1L),
                mediaType,
                options,
                "PRIORITY_NORMAL",
                null
        );
    }

    private static InputStream streamOf(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    // ---- supports ------------------------------------------------------------------------------

    @Test
    void shouldReturnTrueForTextPlain() {
        assertThat(ADAPTER.supports("text/plain")).isTrue();
    }

    @Test
    void shouldReturnTrueForTextMarkdown() {
        assertThat(ADAPTER.supports("text/markdown")).isTrue();
    }

    @Test
    void shouldReturnTrueForTextHtml() {
        assertThat(ADAPTER.supports("text/html")).isTrue();
    }

    @Test
    void shouldReturnTrueForApplicationEpubZip() {
        assertThat(ADAPTER.supports("application/epub+zip")).isTrue();
    }

    @Test
    void shouldReturnFalseForApplicationPdf() {
        assertThat(ADAPTER.supports("application/pdf")).isFalse();
    }

    @Test
    void shouldReturnFalseForAudioMpeg() {
        assertThat(ADAPTER.supports("audio/mpeg")).isFalse();
    }

    // ---- extract -------------------------------------------------------------------------------

    @Test
    void shouldExtractTextFromPlainTextContent() throws Exception {
        String content = "Hello world\n\nSecond paragraph";
        InputStream source = streamOf(content);

        AdapterResult result = ADAPTER.extract(requestFor("text/plain"), source);

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.document()).isNotNull();
        assertThat(result.document().flattenedText()).contains("Hello world");
        assertThat(result.document().elements()).hasSizeGreaterThanOrEqualTo(1);
    }

    // ---- feature states ------------------------------------------------------------------------

    @Test
    void shouldReportTextAppliedInFeatureStates() throws Exception {
        AdapterResult result = ADAPTER.extract(
                requestFor("text/plain"),
                streamOf("sample text")
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.featureStates()).containsEntry("text", FeatureOutcome.APPLIED);
    }

    @Test
    void shouldReportOcrNotRequestedWhenOcrOptionIsNull() throws Exception {
        ExtractionOptions options = ExtractionOptions.defaults(); // ocr == null
        AdapterResult result = ADAPTER.extract(
                requestFor("text/plain", options),
                streamOf("sample text")
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.featureStates()).containsEntry("ocr", FeatureOutcome.NOT_REQUESTED);
    }

    @Test
    void shouldReportOcrNotApplicableWhenOcrOptionIsTrue() throws Exception {
        ExtractionOptions options = new ExtractionOptions(
                true,  // ocr explicitly requested
                null, null, null, null, null, null
        );
        AdapterResult result = ADAPTER.extract(
                requestFor("text/plain", options),
                streamOf("sample text")
        );

        assertThat(result.isSuccess()).isTrue();
        // Text already has digital characters; OCR is not applicable.
        assertThat(result.featureStates()).containsEntry("ocr", FeatureOutcome.NOT_APPLICABLE);
    }
}
