package synanton.extraction.adapter.in.grpc;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import synanton.extraction.domain.model.SyncExtractionOutcome;
import synanton.extraction.spi.model.ContentOrigin;
import synanton.extraction.spi.model.ElementBounds;
import synanton.extraction.spi.model.ElementType;
import synanton.extraction.spi.model.ExtractionFailure;
import synanton.extraction.spi.model.ExtractionOptions;
import synanton.extraction.spi.model.FeatureOutcome;
import synanton.extraction.spi.model.NormalizedDocument;
import synanton.extraction.spi.model.NormalizedElement;
import synanton.extraction.spi.model.ObjectRef;
import synanton.extraction.v1.BoundingBox;
import synanton.extraction.v1.ContentProvenance;
import synanton.extraction.v1.DocumentElement;
import synanton.extraction.v1.DocumentElementType;
import synanton.extraction.v1.DocumentPayload;
import synanton.extraction.v1.ElementLocation;
import synanton.extraction.v1.ExtractionError;
import synanton.extraction.v1.ExtractionErrorCatalogue;
import synanton.extraction.v1.ExtractionErrorCode;
import synanton.extraction.v1.ExtractionResult;
import synanton.extraction.v1.ExtractionStatus;
import synanton.extraction.v1.FeatureState;
import synanton.extraction.v1.ObjectReference;
import synanton.extraction.v1.PayloadDescriptor;
import synanton.extraction.v1.ResultProvenance;
import synanton.extraction.v1.SerializationFormat;
import synanton.extraction.v1.StructuredPayload;
import synanton.extraction.v1.SubmitExtractionRequest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

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

    static ExtractionOptions toOptions(synanton.extraction.v1.ExtractionOptions options) {
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
            DocumentPayload payload = toDocumentPayload(outcome.document());
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

        return builder.build();
    }

    static DocumentPayload toDocumentPayload(NormalizedDocument document) {
        DocumentPayload.Builder builder = DocumentPayload.newBuilder()
                .setMediaType(nvl(document.mediaType()))
                .setFlattenedText(nvl(document.flattenedText()));
        document.metadata().forEach(builder::putMetadata);
        for (NormalizedElement el : document.elements()) {
            builder.addElements(toElement(el));
        }
        return builder.build();
    }

    private static DocumentElement toElement(NormalizedElement el) {
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
        return b.build();
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
