package org.synanton.extraction.contract;

import org.junit.jupiter.api.Test;
import org.synanton.extraction.v1.CanonicalTextIdentity;
import org.synanton.extraction.v1.CanonicalTextRange;
import org.synanton.extraction.v1.CoordinateSpace;
import org.synanton.extraction.v1.DocumentClass;
import org.synanton.extraction.v1.DocumentElement;
import org.synanton.extraction.v1.DocumentElementType;
import org.synanton.extraction.v1.DocumentIdentity;
import org.synanton.extraction.v1.DocumentPayload;
import org.synanton.extraction.v1.ExecutionAttribution;
import org.synanton.extraction.v1.ExecutionLinkKind;
import org.synanton.extraction.v1.ExecutionOutcome;
import org.synanton.extraction.v1.ExtractionProvenance;
import org.synanton.extraction.v1.QualityReport;
import org.synanton.extraction.v1.SourceDescriptor;
import org.synanton.extraction.v1.SourceLocation;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks the approved #22 schema design to field numbers and presence semantics. */
class SchemaDesignContractTest {

    @Test
    void documentPayloadAdditionsUseApprovedFieldNumbers() {
        assertThat(DocumentPayload.getDescriptor().findFieldByNumber(5).getName()).isEqualTo("identity");
        assertThat(DocumentPayload.getDescriptor().findFieldByNumber(6).getName()).isEqualTo("source");
        assertThat(DocumentPayload.getDescriptor().findFieldByNumber(7).getName()).isEqualTo("provenance");
        assertThat(DocumentPayload.getDescriptor().findFieldByNumber(8).getName()).isEqualTo("projections");
    }

    @Test
    void documentElementAdditionsUseApprovedFieldNumbers() {
        assertThat(DocumentElement.getDescriptor().findFieldByNumber(10).getName()).isEqualTo("source_locations");
        assertThat(DocumentElement.getDescriptor().findFieldByNumber(11).getName()).isEqualTo("canonical_text_range");
        assertThat(DocumentElement.getDescriptor().findFieldByNumber(12).getName()).isEqualTo("execution_ids");
        assertThat(DocumentElement.getDescriptor().findFieldByNumber(13).getName()).isEqualTo("execution_attribution");
    }

    @Test
    void approvedEnumsAndValuesArePresent() {
        assertThat(DocumentElementType.ELEMENT_SECTION.getNumber()).isEqualTo(16);
        assertThat(DocumentElementType.ELEMENT_FIGURE.getNumber()).isEqualTo(17);
        assertThat(DocumentElementType.ELEMENT_UNKNOWN.getNumber()).isEqualTo(18);
        assertThat(DocumentClass.DOCUMENT_CLASS_TRANSCRIPT.getNumber()).isEqualTo(3);
        assertThat(ExecutionAttribution.EXECUTION_ATTRIBUTION_NONE_KNOWN.getNumber()).isEqualTo(4);
        assertThat(ExecutionOutcome.EXECUTION_OUTCOME_FAILED.getNumber()).isEqualTo(3);
        assertThat(ExecutionLinkKind.EXECUTION_LINK_MERGE.getNumber()).isEqualTo(4);
    }

    @Test
    void textRangeDoesNotRepresentCanonicalCoordinates() {
        assertThat(CoordinateSpace.values())
            .extracting(CoordinateSpace::name)
            .doesNotContain("COORDINATE_SPACE_CANONICAL_TEXT");
    }

    @Test
    void qualityValuesDistinguishUnsetFromMeasuredZero() {
        QualityReport unset = QualityReport.newBuilder().build();
        QualityReport zero = QualityReport.newBuilder().setCompleteness(0.0).build();

        assertThat(unset.hasCompleteness()).isFalse();
        assertThat(zero.hasCompleteness()).isTrue();
        assertThat(unset.hasConfidence()).isFalse();
    }

    @Test
    void newDocumentPayloadRoundTripsWithTypedLocationsAndProvenance() throws Exception {
        CanonicalTextIdentity identity = CanonicalTextIdentity.newBuilder()
            .setCanonicalTextId("canon-1")
            .setCanonicalTextDigest("sha256:def")
            .setEncoding("UTF-8")
            .setSourceDocumentId("doc-1")
            .setSourceDigest("sha256:abc")
            .setSourceVersion("v1")
            .setExtractionExecutionId("exec-1")
            .setNormalizationProfile("")
            .setNormalizationVersion("15.1.0")
            .setProjectionContractId("canonical-text")
            .setProjectionContractVersion("1")
            .setLineEndings(org.synanton.extraction.v1.LineEndingPolicy.LINE_ENDING_LF)
            .setNormalization(org.synanton.extraction.v1.TextNormalization.TEXT_NORMALIZATION_NFC)
            .build();

        DocumentPayload payload = DocumentPayload.newBuilder()
            .setMediaType("text/markdown")
            .setIdentity(DocumentIdentity.newBuilder()
                .setDocumentId("doc-1")
                .setDocumentClass(DocumentClass.DOCUMENT_CLASS_TEXT))
            .setSource(SourceDescriptor.newBuilder()
                .setMediaType("text/markdown")
                .setSourceFormat("markdown")
                .setSourceDigest("sha256:abc")
                .setSourceVersion("v1"))
            .setProvenance(ExtractionProvenance.newBuilder().setPrimaryExecutionId("exec-1"))
            .addElements(DocumentElement.newBuilder()
                .setId("e1")
                .setType(DocumentElementType.ELEMENT_SECTION)
                .addSourceLocations(SourceLocation.newBuilder()
                    .setLocationId("loc-1")
                    .setPrecision(org.synanton.extraction.v1.LocationPrecision.LOCATION_PRECISION_EXACT)
                    .setReproducibility(org.synanton.extraction.v1.Reproducibility.REPRODUCIBILITY_EXACT_FROM_SOURCE))
                .setCanonicalTextRange(CanonicalTextRange.newBuilder()
                    .setIdentity(identity)
                    .setUnit(org.synanton.extraction.v1.OffsetUnit.OFFSET_UNIT_UTF16_CODE_UNIT)
                    .setStart(0)
                    .setEnd(4)
                    .setLineEndings(org.synanton.extraction.v1.LineEndingPolicy.LINE_ENDING_LF)
                    .setNormalization(org.synanton.extraction.v1.TextNormalization.TEXT_NORMALIZATION_NFC))
                .addExecutionIds("exec-1")
                .setExecutionAttribution(ExecutionAttribution.EXECUTION_ATTRIBUTION_EXPLICIT))
            .build();

        assertThat(DocumentPayload.parseFrom(payload.toByteString())).isEqualTo(payload);
    }
}
