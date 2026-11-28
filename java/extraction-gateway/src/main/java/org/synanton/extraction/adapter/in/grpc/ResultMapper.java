package org.synanton.extraction.adapter.in.grpc;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import org.synanton.extraction.domain.model.SyncExtractionOutcome;
import org.synanton.extraction.spi.model.CanonicalRange;
import org.synanton.extraction.spi.model.CanonicalText;
import org.synanton.extraction.spi.model.ContentOrigin;
import org.synanton.extraction.spi.model.ElementBounds;
import org.synanton.extraction.spi.model.ElementType;
import org.synanton.extraction.spi.model.ExtractionFailure;
import org.synanton.extraction.spi.model.ExtractionOptions;
import org.synanton.extraction.spi.model.FeatureOutcome;
import org.synanton.extraction.spi.model.NormalizedDocument;
import org.synanton.extraction.spi.model.NormalizedElement;
import org.synanton.extraction.spi.model.NormalizedSourceDescriptor;
import org.synanton.extraction.spi.model.ObjectRef;
import org.synanton.extraction.spi.model.SourceSpan;
import org.synanton.extraction.v1.BindingKind;
import org.synanton.extraction.v1.BoundingBox;
import org.synanton.extraction.v1.CanonicalTextIdentity;
import org.synanton.extraction.v1.CanonicalTextRange;
import org.synanton.extraction.v1.ContentProvenance;
import org.synanton.extraction.v1.CoordinateSpace;
import org.synanton.extraction.v1.DocumentElement;
import org.synanton.extraction.v1.DocumentElementType;
import org.synanton.extraction.v1.DocumentIdentity;
import org.synanton.extraction.v1.DocumentPayload;
import org.synanton.extraction.v1.DocumentProjection;
import org.synanton.extraction.v1.ElementLocation;
import org.synanton.extraction.v1.LineEndingPolicy;
import org.synanton.extraction.v1.ExtractionError;
import org.synanton.extraction.v1.ExtractionErrorCatalogue;
import org.synanton.extraction.v1.ExtractionErrorCode;
import org.synanton.extraction.v1.ExtractionResult;
import org.synanton.extraction.v1.ExtractionStatus;
import org.synanton.extraction.v1.FeatureState;
import org.synanton.extraction.v1.ObjectReference;
import org.synanton.extraction.v1.LocationPrecision;
import org.synanton.extraction.v1.OffsetUnit;
import org.synanton.extraction.v1.PayloadDescriptor;
import org.synanton.extraction.v1.ProjectionOutcome;
import org.synanton.extraction.v1.Reproducibility;
import org.synanton.extraction.v1.ResourceUsage;
import org.synanton.extraction.v1.ResultProvenance;
import org.synanton.extraction.v1.SerializationFormat;
import org.synanton.extraction.v1.SourceBinding;
import org.synanton.extraction.v1.SourceDescriptor;
import org.synanton.extraction.v1.SourceLocation;
import org.synanton.extraction.v1.StructuredPayload;
import org.synanton.extraction.v1.SubmitExtractionRequest;
import org.synanton.extraction.v1.TextNormalization;
import org.synanton.extraction.v1.TextRange;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

final class ResultMapper {

    private ResultMapper() {
    }

    static ObjectRef toObjectRef(ObjectReference source) {
        return new ObjectRef(
                source.getBucket(),
                source.getKey(),
                source.getVersion(),
                source.getSha256(),
                source.getSizeBytes());
    }

    static ExtractionOptions toOptions(org.synanton.extraction.v1.ExtractionOptions options) {
        if (options == null) {
            return ExtractionOptions.defaults();
        }
        return new ExtractionOptions(
                options.hasOcr() ? options.getOcr() : null,
                options.hasTranscription() ? options.getTranscription() : null,
                options.hasLayout() ? options.getLayout() : null,
                options.hasTables() ? options.getTables() : null,
                options.hasEmbeddedImages() ? options.getEmbeddedImages() : null,
                options.hasSceneAnalysis() ? options.getSceneAnalysis() : null,
                options.hasLanguage() ? options.getLanguage() : null);
    }

    static Instant expiresAt(SubmitExtractionRequest request) {
        if (!request.hasExpiresAt()) {
            return null;
        }
        Timestamp ts = request.getExpiresAt();
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
    }

    static ExtractionResult toResult(SyncExtractionOutcome outcome, SubmitExtractionRequest request) {
        ExtractionResult.Builder builder = ExtractionResult.newBuilder()
                .setOperationId(outcome.operationId())
                .setItemIndex(0)
                .setContentRefId(nvl(outcome.contentRefId()))
                .setStatus(toStatus(outcome.status()));

        if (outcome.failure() != null) {
            builder.setError(toError(outcome.failure()));
        }

        if (outcome.document() != null) {
            DocumentPayload payload = toDocumentPayload(
                    outcome.document(), outcome.contentRefId(), outcome.operationId());
            byte[] bytes = payload.toByteArray();
            builder.setFlattenedText(nvl(outcome.document().flattenedText()));
            builder.setPayload(StructuredPayload.newBuilder()
                    .setPayloadDescriptor(PayloadDescriptor.newBuilder()
                            .setSchemaId("synanton.extraction.document")
                            .setSchemaVersion("1.0")
                            .setProcessorId(nvl(outcome.processorId(), "document-adapter"))
                            .setProcessorVersion("1.0")
                            .setFormat(SerializationFormat.SERIALIZATION_PROTOBUF)
                            .setPayloadDigest(sha256Hex(bytes))
                            .build())
                    .setInlineContent(ByteString.copyFrom(bytes))
                    .build());
        }

        outcome.featureStates().forEach((k, v) -> builder.putFeatureStates(k, toFeatureState(v)));
        request.getItem().getMetadataMap().forEach(builder::putMetadata);
        request.getItem().getBusinessTagsMap().forEach(builder::putBusinessTags);

        builder.setProvenance(ResultProvenance.newBuilder()
                .setContentRefId(nvl(outcome.contentRefId()))
                .setSourceSha256(nvl(outcome.sourceSha256()))
                .setSource(request.getItem().getSource())
                .setExtractedAt(Timestamp.newBuilder()
                        .setSeconds(Instant.now().getEpochSecond())
                        .setNanos(Instant.now().getNano())
                        .build())
                .build());

        if (outcome.wallMs() > 0 || outcome.outputChars() > 0 || outcome.inputBytes() > 0) {
            builder.setUsage(ResourceUsage.newBuilder()
                    .setWallMs(outcome.wallMs())
                    .setCpuNs(outcome.cpuNs())
                    .setInputBytes(outcome.inputBytes())
                    .setOutputChars(outcome.outputChars())
                    .build());
        }

        return builder.build();
    }

    static ExtractionResult toResult(SyncExtractionOutcome outcome, String contentRefId, int itemIndex) {
        ExtractionResult.Builder builder = ExtractionResult.newBuilder()
                .setOperationId(outcome.operationId())
                .setItemIndex(itemIndex)
                .setContentRefId(nvl(contentRefId))
                .setStatus(toStatus(outcome.status()));

        if (outcome.failure() != null) {
            builder.setError(toError(outcome.failure()));
        }

        if (outcome.document() != null) {
            DocumentPayload payload = toDocumentPayload(
                    outcome.document(), contentRefId, outcome.operationId());
            byte[] bytes = payload.toByteArray();
            builder.setFlattenedText(nvl(outcome.document().flattenedText()));
            builder.setPayload(StructuredPayload.newBuilder()
                    .setPayloadDescriptor(PayloadDescriptor.newBuilder()
                            .setSchemaId("synanton.extraction.document")
                            .setSchemaVersion("1.0")
                            .setProcessorId(nvl(outcome.processorId(), "document-adapter"))
                            .setProcessorVersion("1.0")
                            .setFormat(SerializationFormat.SERIALIZATION_PROTOBUF)
                            .setPayloadDigest(sha256Hex(bytes))
                            .build())
                    .setInlineContent(ByteString.copyFrom(bytes))
                    .build());
        }

        outcome.featureStates().forEach((k, v) -> builder.putFeatureStates(k, toFeatureState(v)));

        builder.setProvenance(ResultProvenance.newBuilder()
                .setContentRefId(nvl(outcome.contentRefId()))
                .setSourceSha256(nvl(outcome.sourceSha256()))
                .setExtractedAt(Timestamp.newBuilder()
                        .setSeconds(Instant.now().getEpochSecond())
                        .setNanos(Instant.now().getNano())
                        .build())
                .build());

        if (outcome.wallMs() > 0 || outcome.outputChars() > 0 || outcome.inputBytes() > 0) {
            builder.setUsage(ResourceUsage.newBuilder()
                    .setWallMs(outcome.wallMs())
                    .setCpuNs(outcome.cpuNs())
                    .setInputBytes(outcome.inputBytes())
                    .setOutputChars(outcome.outputChars())
                    .build());
        }

        return builder.build();
    }

    static DocumentPayload toDocumentPayload(NormalizedDocument document) {
        return toDocumentPayload(document, null, null);
    }

    static DocumentPayload toDocumentPayload(
            NormalizedDocument document, String documentId, String executionId) {
        if (document.source() == null
                && document.elements().stream().anyMatch(el -> !el.sourceSpans().isEmpty())) {
            throw new IllegalArgumentException(
                    "source locations require an immutable document source identity");
        }
        DocumentPayload.Builder builder = DocumentPayload.newBuilder()
                .setMediaType(nvl(document.mediaType()))
                .setFlattenedText(nvl(document.flattenedText()));
        document.metadata().forEach(builder::putMetadata);
        NormalizedSourceDescriptor source = document.source();
        if (source != null) {
            builder.setSource(SourceDescriptor.newBuilder()
                    .setMediaType(nvl(source.mediaType()))
                    .setSourceFormat(nvl(source.sourceFormat()))
                    .setSourceDigest(nvl(source.sourceDigest()))
                    .setSourceVersion(nvl(source.sourceVersion()))
                    .setFilename(nvl(source.filename()))
                    .build());
        }

        CanonicalTextIdentity canonicalIdentity = null;
        Map<String, CanonicalRange> canonicalRanges = Map.of();
        CanonicalText canonicalText = document.canonicalText();
        if (canonicalText != null) {
            String canonicalDocumentId = resolveDocumentId(document, documentId);
            byte[] canonicalBytes = canonicalText.text().getBytes(StandardCharsets.UTF_8);
            String canonicalDigest = "sha256:" + sha256Hex(canonicalBytes);
            canonicalIdentity = CanonicalTextIdentity.newBuilder()
                    .setCanonicalTextId("ct-v1:" + canonicalDigest)
                    .setCanonicalTextDigest(canonicalDigest)
                    .setEncoding("UTF-8")
                    .setSourceDocumentId(canonicalDocumentId)
                    .setSourceDigest(source == null ? "" : nvl(source.sourceDigest()))
                    .setSourceVersion(source == null ? "" : nvl(source.sourceVersion()))
                    .setExtractionExecutionId(nvl(executionId))
                    .setNormalizationProfile(canonicalText.normalizationProfile())
                    .setNormalizationVersion(canonicalText.normalizationVersion())
                    .setProjectionContractId("synanton.canonical-text")
                    .setProjectionContractVersion("1")
                    .setLineEndings(LineEndingPolicy.LINE_ENDING_LF)
                    .setNormalization(TextNormalization.TEXT_NORMALIZATION_NFC)
                    .build();
            canonicalRanges = canonicalText.elementRanges();
            builder.setIdentity(DocumentIdentity.newBuilder().setDocumentId(canonicalDocumentId));
            builder.addProjections(DocumentProjection.newBuilder()
                    .setProjectionId("canonical-text")
                    .setFormat("text/plain; charset=utf-8")
                    .setContractId("synanton.canonical-text")
                    .setContractVersion("1")
                    .setOutcome(ProjectionOutcome.PROJECTION_OUTCOME_SUCCESS_LOSSLESS)
                    .setDigest(canonicalDigest)
                    .setInlineContent(ByteString.copyFrom(canonicalBytes)));
        }

        for (NormalizedElement el : document.elements()) {
            builder.addElements(toElement(el, canonicalIdentity, canonicalRanges));
        }
        return builder.build();
    }

    private static String resolveDocumentId(NormalizedDocument document, String preferred) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        if (document.source() != null && document.source().filename() != null
                && !document.source().filename().isBlank()) {
            return document.source().filename();
        }
        return "document";
    }

    private static DocumentElement toElement(
            NormalizedElement el,
            CanonicalTextIdentity canonicalIdentity,
            Map<String, CanonicalRange> canonicalRanges) {
        DocumentElement.Builder b = DocumentElement.newBuilder()
                .setId(nvl(el.id()))
                .setType(toElementType(el.type()))
                .setText(nvl(el.text()))
                .setProvenance(toProvenance(el.contentOrigin()))
                .setLevel(el.level());
        el.childIds().forEach(b::addChildIds);
        el.attributes().forEach(b::putAttributes);
        if (el.alternateRepresentation() != null) {
            b.setAlternateRepresentation(el.alternateRepresentation());
        }
        ElementBounds bounds = el.bounds();
        if (bounds != null) {
            b.setLocation(ElementLocation.newBuilder()
                    .setPage(bounds.page())
                    .setBbox(BoundingBox.newBuilder()
                            .setX0(bounds.x0())
                            .setY0(bounds.y0())
                            .setX1(bounds.x1())
                            .setY1(bounds.y1())
                            .build())
                    .build());
        }
        el.sourceSpans().forEach(span -> b.addSourceLocations(toSourceLocation(span)));
        if (canonicalIdentity != null) {
            CanonicalRange range = canonicalRanges.get(el.id());
            if (range != null) {
                b.setCanonicalTextRange(CanonicalTextRange.newBuilder()
                        .setIdentity(canonicalIdentity)
                        .setUnit(OffsetUnit.OFFSET_UNIT_UTF16_CODE_UNIT)
                        .setStart(range.start())
                        .setEnd(range.end())
                        .setNormalization(TextNormalization.TEXT_NORMALIZATION_NFC)
                        .setLineEndings(LineEndingPolicy.LINE_ENDING_LF));
            }
        }
        return b.build();
    }

    private static SourceLocation toSourceLocation(SourceSpan span) {
        return SourceLocation.newBuilder()
                .setLocationId(span.locationId())
                .setSourceBinding(SourceBinding.newBuilder()
                        .setKind(BindingKind.BINDING_KIND_INHERIT_DOCUMENT))
                .setPrecision(LocationPrecision.LOCATION_PRECISION_EXACT)
                .setReproducibility(Reproducibility.REPRODUCIBILITY_EXACT_FROM_SOURCE)
                .setTextRange(TextRange.newBuilder()
                        .setUnit(OffsetUnit.OFFSET_UNIT_UTF8_BYTE)
                        .setCoordinateSpace(CoordinateSpace.COORDINATE_SPACE_ORIGINAL_SOURCE)
                        .setStart(span.startByte())
                        .setEnd(span.endByte())
                        .setNormalization(TextNormalization.TEXT_NORMALIZATION_NONE)
                        .setLineEndings(LineEndingPolicy.LINE_ENDING_ORIGINAL))
                .build();
    }

    private static ExtractionStatus toStatus(SyncExtractionOutcome.OutcomeStatus status) {
        return switch (status) {
            case COMPLETED -> ExtractionStatus.STATUS_COMPLETED;
            case PARTIAL -> ExtractionStatus.STATUS_PARTIAL;
            case EXPIRED -> ExtractionStatus.STATUS_EXPIRED;
            case FAILED -> ExtractionStatus.STATUS_FAILED;
        };
    }

    private static ExtractionError toError(ExtractionFailure failure) {
        ExtractionErrorCode code;
        try {
            code = ExtractionErrorCode.valueOf(failure.errorCode());
        } catch (IllegalArgumentException e) {
            code = ExtractionErrorCode.ERROR_INTERNAL_ERROR;
        }
        return ExtractionError.newBuilder()
                .setCode(code)
                .setDiagnostic(nvl(failure.diagnostic()))
                .setRetryable(ExtractionErrorCatalogue.isRetryable(code))
                .build();
    }

    private static FeatureState toFeatureState(FeatureOutcome outcome) {
        return switch (outcome) {
            case APPLIED -> FeatureState.FEATURE_APPLIED;
            case NOT_REQUESTED -> FeatureState.FEATURE_NOT_REQUESTED;
            case NOT_APPLICABLE -> FeatureState.FEATURE_NOT_APPLICABLE;
            case UNSUPPORTED -> FeatureState.FEATURE_UNSUPPORTED;
            case FAILED -> FeatureState.FEATURE_FAILED;
            case PARTIAL -> FeatureState.FEATURE_PARTIAL;
        };
    }

    private static DocumentElementType toElementType(ElementType type) {
        if (type == null) {
            return DocumentElementType.ELEMENT_TYPE_UNSPECIFIED;
        }
        return switch (type) {
            case PARAGRAPH -> DocumentElementType.ELEMENT_PARAGRAPH;
            case HEADING -> DocumentElementType.ELEMENT_HEADING;
            case LIST -> DocumentElementType.ELEMENT_LIST;
            case LIST_ITEM -> DocumentElementType.ELEMENT_LIST_ITEM;
            case TABLE -> DocumentElementType.ELEMENT_TABLE;
            case TABLE_ROW -> DocumentElementType.ELEMENT_TABLE_ROW;
            case TABLE_CELL -> DocumentElementType.ELEMENT_TABLE_CELL;
            case IMAGE -> DocumentElementType.ELEMENT_IMAGE;
            case FORMULA -> DocumentElementType.ELEMENT_FORMULA;
            case CAPTION -> DocumentElementType.ELEMENT_CAPTION;
            case FOOTNOTE -> DocumentElementType.ELEMENT_FOOTNOTE;
            case HEADER -> DocumentElementType.ELEMENT_HEADER;
            case FOOTER -> DocumentElementType.ELEMENT_FOOTER;
            case CODE_BLOCK -> DocumentElementType.ELEMENT_CODE_BLOCK;
            case PAGE_BREAK -> DocumentElementType.ELEMENT_PAGE_BREAK;
        };
    }

    private static ContentProvenance toProvenance(ContentOrigin origin) {
        if (origin == null) {
            return ContentProvenance.PROVENANCE_UNSPECIFIED;
        }
        return switch (origin) {
            case EMBEDDED_TEXT -> ContentProvenance.PROVENANCE_EMBEDDED_TEXT;
            case OCR -> ContentProvenance.PROVENANCE_OCR;
            case TAGGED_STRUCTURE -> ContentProvenance.PROVENANCE_TAGGED_STRUCTURE;
            case LAYOUT_INFERRED -> ContentProvenance.PROVENANCE_LAYOUT_INFERRED;
            case GENERATED -> ContentProvenance.PROVENANCE_GENERATED;
            case TRANSCRIBED -> ContentProvenance.PROVENANCE_TRANSCRIBED;
        };
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static String nvl(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
