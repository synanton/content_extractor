package org.synanton.extraction.adapter.document.pdf;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.synanton.extraction.spi.model.AdapterResult;
import org.synanton.extraction.spi.model.ElementType;
import org.synanton.extraction.spi.model.ExtractionOptions;
import org.synanton.extraction.spi.model.ExtractionRequest;
import org.synanton.extraction.spi.model.NormalizedDocument;
import org.synanton.extraction.spi.model.ObjectRef;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real {@code org.opendataloader:opendataloader-pdf-core} library against a
 * real (spec-valid) PDF fixture — end to end through {@link OpenDataLoaderClient},
 * {@link OpenDataLoaderNormalizer} and {@link PdfModalityAdapter}. This is what actually
 * proves the OpenDataLoader integration works; {@link OpenDataLoaderNormalizerTest} only
 * covers mapping logic against hand-built objects, which can't catch a schema mismatch
 * against the real library (as happened before this test existed — the adapter previously
 * called a non-existent HTTP service and no test caught it).
 */
class OpenDataLoaderIntegrationTest {

    private static final Path FIXTURE = Path.of(
            "src/test/resources/fixtures/quarterly-report.pdf");

    @AfterAll
    static void shutdownLibrary() {
        org.opendataloader.pdf.api.OpenDataLoaderPDF.shutdown();
    }

    @Test
    void clientShouldExtractRealStructureFromFixture() throws Exception {
        OpenDataLoaderClient client = new OpenDataLoaderClient(new ObjectMapper());
        byte[] pdfBytes = Files.readAllBytes(FIXTURE);

        OdlResponse response = client.extract(pdfBytes);

        assertThat(response.getNumberOfPages()).isEqualTo(1);
        assertThat(response.getKids()).isNotEmpty();
        assertThat(response.getKids())
                .extracting(OdlElement::getType)
                .allMatch(type -> type != null && !type.isBlank());

        OpenDataLoaderNormalizer normalizer = new OpenDataLoaderNormalizer();
        NormalizedDocument document = normalizer.normalize(response, "application/pdf");

        assertThat(document.flattenedText()).contains("Quarterly Report");
        assertThat(document.elements()).isNotEmpty();
        assertThat(document.elements())
                .extracting(el -> el.type())
                .contains(ElementType.HEADING);
    }

    @Test
    void adapterShouldSucceedEndToEndForRealPdf() throws Exception {
        PdfModalityAdapter adapter = new PdfModalityAdapter(
                new OpenDataLoaderClient(new ObjectMapper()));

        ExtractionRequest request = new ExtractionRequest(
                "op-1", "tenant-1", "idem-1", "content-ref-1",
                new ObjectRef("bucket", "key", null, null, 0L),
                "application/pdf", ExtractionOptions.defaults(), "normal",
                Instant.now().plusSeconds(3600));
        InputStream source = new ByteArrayInputStream(Files.readAllBytes(FIXTURE));

        AdapterResult result = adapter.extract(request, source);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.document().flattenedText()).contains("Quarterly Report");
    }
}
