package org.synanton.extraction.adapter.in.grpc;

import org.junit.jupiter.api.Test;
import org.synanton.extraction.spi.model.ContentOrigin;
import org.synanton.extraction.spi.model.ElementBounds;
import org.synanton.extraction.spi.model.ElementType;
import org.synanton.extraction.spi.model.NormalizedDocument;
import org.synanton.extraction.spi.model.NormalizedElement;
import org.synanton.extraction.spi.model.NormalizedSourceDescriptor;
import org.synanton.extraction.spi.model.SourceSpan;
import org.synanton.extraction.v1.DocumentElement;
import org.synanton.extraction.v1.DocumentPayload;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultMapperTest {

    @Test
    void mapsVerifiedSourceIdentityAndSpansToTheDocumentPayload() {
        NormalizedDocument document = new NormalizedDocument(
                "text/markdown",
                Map.of(),
                List.of(new NormalizedElement(
                        "e1", ElementType.HEADING, ElementBounds.absent(),
                        "Title", ContentOrigin.EMBEDDED_TEXT, 1,
                        List.of(), Map.of(), null,
                        List.of(new SourceSpan("span-e1", 0, 7)))),
                "Title",
                new NormalizedSourceDescriptor(
                        "text/markdown", "markdown", "a".repeat(64), "", "doc.md"));

        DocumentPayload payload = ResultMapper.toDocumentPayload(document);

        assertThat(payload.getSource().getSourceFormat()).isEqualTo("markdown");
        assertThat(payload.getSource().getSourceDigest()).isEqualTo("a".repeat(64));
        assertThat(payload.getIdentity()).isEqualTo(org.synanton.extraction.v1.DocumentIdentity.getDefaultInstance());
        assertThat(payload.getProvenance()).isEqualTo(org.synanton.extraction.v1.ExtractionProvenance.getDefaultInstance());
        assertThat(payload.getProjectionsList()).isEmpty();

        DocumentElement element = payload.getElements(0);
        assertThat(element.getSourceLocationsCount()).isEqualTo(1);
        assertThat(element.getSourceLocations(0).getTextRange().getStart()).isZero();
        assertThat(element.getSourceLocations(0).getTextRange().getEnd()).isEqualTo(7);
        assertThat(element.getSourceLocations(0).getTextRange().getUnit())
                .isEqualTo(org.synanton.extraction.v1.OffsetUnit.OFFSET_UNIT_UTF8_BYTE);
        assertThat(element.getSourceLocations(0).getSourceBinding().getKind())
                .isEqualTo(org.synanton.extraction.v1.BindingKind.BINDING_KIND_INHERIT_DOCUMENT);
        assertThat(element.getSourceLocations(0).getPrecision())
                .isEqualTo(org.synanton.extraction.v1.LocationPrecision.LOCATION_PRECISION_EXACT);
        assertThat(element.getSourceLocations(0).getReproducibility())
                .isEqualTo(org.synanton.extraction.v1.Reproducibility.REPRODUCIBILITY_EXACT_FROM_SOURCE);
        assertThat(element.getSourceLocations(0).getTextRange().getCoordinateSpace())
                .isEqualTo(org.synanton.extraction.v1.CoordinateSpace.COORDINATE_SPACE_ORIGINAL_SOURCE);
        assertThat(element.getSourceLocations(0).getTextRange().getNormalization())
                .isEqualTo(org.synanton.extraction.v1.TextNormalization.TEXT_NORMALIZATION_NONE);
        assertThat(element.getSourceLocations(0).getTextRange().getLineEndings())
                .isEqualTo(org.synanton.extraction.v1.LineEndingPolicy.LINE_ENDING_ORIGINAL);
        assertThat(element.getCanonicalTextRange())
                .isEqualTo(org.synanton.extraction.v1.CanonicalTextRange.getDefaultInstance());
    }

    @Test
    void rejectsSourceSpansWithoutDocumentSourceIdentity() {
        NormalizedDocument document = new NormalizedDocument(
                "text/markdown", Map.of(),
                List.of(new NormalizedElement(
                        "e1", ElementType.HEADING, ElementBounds.absent(),
                        "Title", ContentOrigin.EMBEDDED_TEXT, 1,
                        List.of(), Map.of(), null,
                        List.of(new SourceSpan("span-e1", 0, 7)))),
                "Title");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> ResultMapper.toDocumentPayload(document))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source identity");
    }
}
