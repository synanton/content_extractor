package synanton.extraction.adapter.document.text;

import org.junit.jupiter.api.Test;
import org.synanton.extraction.adapter.document.text.TextModalityAdapter;
import org.synanton.extraction.spi.model.AdapterResult;
import org.synanton.extraction.spi.model.ExtractionOptions;
import org.synanton.extraction.spi.model.ExtractionRequest;
import org.synanton.extraction.spi.model.ElementType;
import org.synanton.extraction.spi.model.NormalizedElement;
import org.synanton.extraction.spi.model.SourceSpan;
import org.synanton.extraction.spi.model.FeatureOutcome;
import org.synanton.extraction.spi.model.ObjectRef;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
    void shouldReportOcrNotApplicableForPlainText() throws Exception {
        ExtractionOptions options = ExtractionOptions.defaults();
        AdapterResult result = ADAPTER.extract(
                requestFor("text/plain", options),
                streamOf("sample text")
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.featureStates()).containsEntry("ocr", FeatureOutcome.NOT_APPLICABLE);
    }

    @Test
    void shouldPreserveMarkdownHeadingsParagraphsAndCode() throws Exception {
        String content = """
                # Guide
                Intro paragraph.

                ## Install
                Run this:

                ```bash
                docker compose up
                ```
                """;

        AdapterResult result = ADAPTER.extract(requestFor("text/markdown"), streamOf(content));

        assertThat(result.isSuccess()).isTrue();
        List<NormalizedElement> elements = result.document().elements();
        assertThat(elements).extracting(NormalizedElement::type)
                .containsExactly(ElementType.HEADING, ElementType.PARAGRAPH,
                        ElementType.HEADING, ElementType.PARAGRAPH, ElementType.CODE_BLOCK);
        assertThat(elements.get(0).level()).isEqualTo(1);
        assertThat(elements.get(2).level()).isEqualTo(2);
        assertThat(elements.get(4).text()).isEqualTo("docker compose up");
        assertThat(elements.get(4).attributes()).containsEntry("language", "bash");
    }

    @Test
    void shouldPreserveMarkdownNestedListsAndParentChildIds() throws Exception {
        String content = """
                - alpha
                  - beta
                  - gamma
                - delta
                """;

        AdapterResult result = ADAPTER.extract(requestFor("text/markdown"), streamOf(content));

        assertThat(result.isSuccess()).isTrue();
        List<NormalizedElement> elements = result.document().elements();
        assertThat(elements).extracting(NormalizedElement::type)
                .containsExactly(ElementType.LIST, ElementType.LIST_ITEM,
                        ElementType.LIST_ITEM, ElementType.LIST_ITEM, ElementType.LIST_ITEM);
        NormalizedElement list = elements.get(0);
        assertThat(list.childIds()).containsExactly("e2", "e5");
        NormalizedElement beta = elements.get(2);
        assertThat(beta.level()).isEqualTo(2);
        assertThat(beta.id()).isEqualTo("e3");
        assertThat(elements.get(1).childIds()).containsExactly("e3", "e4");
    }

    @Test
    void shouldKeepMarkdownCommentsAndFrontMatterOutOfStructuralContent() throws Exception {
        String content = """
                ---
                title: hidden metadata
                ---
                # Visible
                <!-- Author: hidden -->
                Body text.
                """;

        AdapterResult result = ADAPTER.extract(requestFor("text/markdown"), streamOf(content));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.document().elements()).extracting(NormalizedElement::text)
                .containsExactly("Visible", "Body text.");
    }

    @Test
    void shouldKeepBoldPreambleMetadataOutOfStructuralContent() throws Exception {
        String content = """
                # Book
                **Author:** Ada
                **Edition:** 2
                <!-- Document ID: hidden -->
                ## Preface
                Body.
                """;

        AdapterResult result = ADAPTER.extract(requestFor("text/markdown"), streamOf(content));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.document().elements()).extracting(NormalizedElement::text)
                .containsExactly("Book", "Preface", "Body.");
        assertThat(result.document().metadata())
                .containsEntry("Author", "Ada")
                .containsEntry("Edition", "2");
    }

    @Test
    void shouldEmitRawMarkdownSourceRanges() throws Exception {
        String content = "# Title\nBody with **bold**.\n\n- item\n  - nested\n\n```js\nconst x = 1;\n```\n";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        AdapterResult result = ADAPTER.extract(requestFor("text/markdown"),
                new ByteArrayInputStream(bytes));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.document().source()).isNotNull();
        assertThat(result.document().source().sourceFormat()).isEqualTo("markdown");
        assertThat(result.document().source().sourceDigest()).isEqualTo("a".repeat(64));

        assertRaw(result, bytes, ElementType.HEADING, "# Title");
        assertRaw(result, bytes, ElementType.PARAGRAPH, "Body with **bold**.");
        assertRaw(result, bytes, ElementType.LIST, "- item\n  - nested");
        assertRaw(result, bytes, ElementType.CODE_BLOCK, "```js\nconst x = 1;\n```");
    }

    @Test
    void shouldPreserveCrlfBytesWhileNormalizingParserText() throws Exception {
        String content = "# Title\r\nBody\r\n";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        AdapterResult result = ADAPTER.extract(requestFor("text/markdown"),
                new ByteArrayInputStream(bytes));

        assertThat(result.isSuccess()).isTrue();
        NormalizedElement heading = element(result, ElementType.HEADING);
        assertThat(heading.text()).isEqualTo("Title");
        SourceSpan span = heading.sourceSpans().getFirst();
        assertThat(new String(bytes, (int) span.startByte(), (int) (span.endByte() - span.startByte()),
                StandardCharsets.UTF_8)).isEqualTo("# Title");
    }

    @Test
    void shouldKeepSupplementaryUnicodeRangesStable() throws Exception {
        String content = "# 😀 Distributed Systems\n";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        AdapterResult result = ADAPTER.extract(requestFor("text/markdown"),
                new ByteArrayInputStream(bytes));

        assertThat(result.isSuccess()).isTrue();
        assertRaw(result, bytes, ElementType.HEADING, "# 😀 Distributed Systems");
    }

    @Test
    void shouldFailClosedOnMalformedUtf8() throws Exception {
        byte[] malformed = new byte[]{'#', ' ', (byte) 0xC3, (byte) 0x28};

        AdapterResult result = ADAPTER.extract(requestFor("text/markdown"),
                new ByteArrayInputStream(malformed));

        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void shouldRejectMarkdownWithoutSourceIdentity() throws Exception {
        ExtractionRequest noSource = new ExtractionRequest(
                "op-1", "tenant1", "key1", "ref1", null, "text/markdown",
                ExtractionOptions.defaults(), "PRIORITY_NORMAL", null);

        AdapterResult result = ADAPTER.extract(noSource, streamOf("# Title"));

        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void shouldPreserveBomInRawRangeAndStripItFromParserTextAndMetadata() throws Exception {
        String content = "\uFEFF# Title\nBody\n";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        AdapterResult result = ADAPTER.extract(requestFor("text/markdown"),
                new ByteArrayInputStream(bytes));

        assertThat(result.isSuccess()).isTrue();
        NormalizedElement heading = element(result, ElementType.HEADING);
        assertThat(heading.text()).isEqualTo("Title");
        SourceSpan span = heading.sourceSpans().getFirst();
        assertThat(span.startByte()).isZero();
        assertThat(new String(bytes, (int) span.startByte(),
                (int) (span.endByte() - span.startByte()), StandardCharsets.UTF_8))
                .isEqualTo("\uFEFF# Title");
    }

    @Test
    void shouldHandleMultiLineParagraphAndCodeBlockCrlfRanges() throws Exception {
        String content = "Line one\r\nLine two\r\n\r\n```\r\ncode one\r\ncode two\r\n```\r\n";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        AdapterResult result = ADAPTER.extract(requestFor("text/markdown"),
                new ByteArrayInputStream(bytes));

        assertThat(result.isSuccess()).isTrue();
        assertRaw(result, bytes, ElementType.PARAGRAPH, "Line one\r\nLine two");
        assertRaw(result, bytes, ElementType.CODE_BLOCK,
                "```\r\ncode one\r\ncode two\r\n```");
    }

    @Test
    void shouldHandleLoneCrAndMixedLineEndings() throws Exception {
        String content = "# Title\r\r- one\n  - two\r\n";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        AdapterResult result = ADAPTER.extract(requestFor("text/markdown"),
                new ByteArrayInputStream(bytes));

        assertThat(result.isSuccess()).isTrue();
        assertRaw(result, bytes, ElementType.HEADING, "# Title");
        assertRaw(result, bytes, ElementType.LIST, "- one\n  - two");
    }

    @Test
    void shouldExcludeFinalLineTerminatorButKeepInternalTerminators() throws Exception {
        String content = "one\ntwo\n";
        byte[] withTerminator = content.getBytes(StandardCharsets.UTF_8);
        byte[] withoutTerminator = "one\ntwo".getBytes(StandardCharsets.UTF_8);

        AdapterResult a = ADAPTER.extract(requestFor("text/markdown"), new ByteArrayInputStream(withTerminator));
        AdapterResult b = ADAPTER.extract(requestFor("text/markdown"), new ByteArrayInputStream(withoutTerminator));

        assertRaw(a, withTerminator, ElementType.PARAGRAPH, "one\ntwo");
        assertRaw(b, withoutTerminator, ElementType.PARAGRAPH, "one\ntwo");
    }

    @Test
    void shouldNotTreatMarkerPrefixedCodeLineAsClosingFence() throws Exception {
        String content = "```\n```not-a-closing-fence\ncode\n```\n";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        AdapterResult result = ADAPTER.extract(requestFor("text/markdown"),
                new ByteArrayInputStream(bytes));

        assertThat(result.isSuccess()).isTrue();
        NormalizedElement code = element(result, ElementType.CODE_BLOCK);
        assertThat(code.text()).isEqualTo("```not-a-closing-fence\ncode");
        assertRaw(result, bytes, ElementType.CODE_BLOCK,
                "```\n```not-a-closing-fence\ncode\n```");
    }

    private static void assertRaw(AdapterResult result, byte[] bytes, ElementType type, String expected) {
        NormalizedElement element = element(result, type);
        assertThat(element.sourceSpans()).hasSize(1);
        SourceSpan span = element.sourceSpans().getFirst();
        String actual = new String(bytes, (int) span.startByte(),
                (int) (span.endByte() - span.startByte()), StandardCharsets.UTF_8);
        assertThat(actual).isEqualTo(expected);
    }

    private static NormalizedElement element(AdapterResult result, ElementType type) {
        return result.document().elements().stream()
                .filter(element -> element.type() == type)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void shouldFlattenPlainTextMatchingSource() throws Exception {
        String content = "Line one\n\nLine two";
        AdapterResult result = ADAPTER.extract(requestFor("text/plain"), streamOf(content));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.document().flattenedText()).isEqualTo("Line one\nLine two");
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
