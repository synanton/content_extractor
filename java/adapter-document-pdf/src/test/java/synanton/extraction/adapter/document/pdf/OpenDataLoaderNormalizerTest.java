package synanton.extraction.adapter.document.pdf;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import synanton.extraction.spi.model.ContentOrigin;
import synanton.extraction.spi.model.ElementBounds;
import synanton.extraction.spi.model.ElementType;
import synanton.extraction.spi.model.ExtractionOptions;
import synanton.extraction.spi.model.ExtractionRequest;
import synanton.extraction.spi.model.FeatureOutcome;
import synanton.extraction.spi.model.NormalizedDocument;
import synanton.extraction.spi.model.NormalizedElement;
import synanton.extraction.spi.model.ObjectRef;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenDataLoaderNormalizerTest {

    private static final OpenDataLoaderNormalizer NORMALIZER = new OpenDataLoaderNormalizer();

    @Test
    void shouldBuildFlattenedTextFromElementsOnly() throws Exception {
        OdlResponse response = new OdlResponse();
        OdlElement heading = new OdlElement();
        heading.setType("heading");
        heading.setPageNumber(1);
        heading.setHeadingLevel(1);
        heading.setContent(new ObjectMapper().getNodeFactory().textNode("Title"));
        OdlElement paragraph = new OdlElement();
        paragraph.setType("paragraph");
        paragraph.setPageNumber(1);
        paragraph.setContent(new ObjectMapper().getNodeFactory().textNode("Body text"));
        response.setKids(List.of(heading, paragraph));

        NormalizedDocument doc = NORMALIZER.normalize(response, "application/pdf");
        assertThat(doc.flattenedText()).isEqualTo("Title\nBody text");

        OdlElement mutated = new OdlElement();
        mutated.setType("paragraph");
        mutated.setPageNumber(1);
        mutated.setContent(new ObjectMapper().getNodeFactory().textNode("Mutated"));
        response.setKids(List.of(mutated));
        NormalizedDocument mutatedDoc = NORMALIZER.normalize(response, "application/pdf");
        assertThat(mutatedDoc.flattenedText()).isEqualTo("Mutated");
    }

    @Test
    void shouldMapOcrContentOrigin() throws Exception {
        OdlElement ocrParagraph = new OdlElement();
        ocrParagraph.setType("paragraph");
        ocrParagraph.setPageNumber(2);
        ocrParagraph.setContentOrigin("ocr");
        ocrParagraph.setContent(new ObjectMapper().getNodeFactory().textNode("Scanned line"));

        OdlResponse response = new OdlResponse();
        response.setKids(List.of(ocrParagraph));

        NormalizedDocument doc = NORMALIZER.normalize(response, "application/pdf");
        assertThat(doc.elements()).hasSize(1);
        assertThat(doc.elements().getFirst().contentOrigin()).isEqualTo(ContentOrigin.OCR);
    }

    @Test
    void shouldReportHonestFeatureStatesForPdf() {
        PdfModalityAdapter adapter = new PdfModalityAdapter();
        NormalizedDocument document = new NormalizedDocument(
                "application/pdf",
                java.util.Map.of(),
                List.of(new NormalizedElement(
                        "p1-e1", ElementType.PARAGRAPH, ElementBounds.absent(),
                        "Digital text", ContentOrigin.EMBEDDED_TEXT,
                        0, List.of(), java.util.Map.of(), null)),
                "Digital text");

        ExtractionRequest ocrRequest = requestWith(new ExtractionOptions(
                true, null, null, null, null, true, null));

        OdlResponse emptyKids = new OdlResponse();
        emptyKids.setKids(List.of());

        Map<String, FeatureOutcome> states = invokeFeatureStates(adapter, ocrRequest, document, emptyKids);
        assertThat(states).containsEntry("ocr", FeatureOutcome.FAILED);
        assertThat(states).containsEntry("sceneAnalysis", FeatureOutcome.UNSUPPORTED);
    }

    private static ExtractionRequest requestWith(ExtractionOptions options) {
        return new ExtractionRequest(
                "op-1", "tenant", "idem", "ref",
                new ObjectRef("b", "k", null, null, 1L),
                "application/pdf", options, "normal", Instant.now().plusSeconds(60));
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, FeatureOutcome> invokeFeatureStates(
            PdfModalityAdapter adapter,
            ExtractionRequest request,
            NormalizedDocument document,
            OdlResponse response) {
        try {
            var m = PdfModalityAdapter.class.getDeclaredMethod(
                    "buildFeatureStates", ExtractionRequest.class, NormalizedDocument.class, OdlResponse.class);
            m.setAccessible(true);
            return (java.util.Map<String, FeatureOutcome>) m.invoke(adapter, request, document, response);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
