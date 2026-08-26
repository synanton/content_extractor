package synanton.extraction.spi.model;

/**
 * A contract-level failure. {@code errorCode} maps to {@code ExtractionErrorCode}.
 * {@code diagnostic} is operator detail and MUST NOT be parsed by callers (rule §24).
 *
 * @param errorCode  symbolic error code that maps to an {@code ExtractionErrorCode} enum constant
 * @param diagnostic human-readable operator detail; not to be parsed by callers
 */
public record ExtractionFailure(String errorCode, String diagnostic) {

    /**
     * Creates a failure indicating the source media type is not supported by any registered adapter.
     *
     * @param mediaType the unsupported IANA media type
     * @return an {@link ExtractionFailure} with code {@code ERROR_UNSUPPORTED_MEDIA_TYPE}
     */
    public static ExtractionFailure unsupportedMediaType(String mediaType) {
        return new ExtractionFailure(
                "ERROR_UNSUPPORTED_MEDIA_TYPE",
                "No adapter supports media type: " + mediaType);
    }

    /**
     * Creates a failure indicating that the extraction process itself encountered an error.
     *
     * @param diagnostic operator-level detail about the failure cause
     * @return an {@link ExtractionFailure} with code {@code ERROR_EXTRACTION_FAILED}
     */
    public static ExtractionFailure extractionFailed(String diagnostic) {
        return new ExtractionFailure("ERROR_EXTRACTION_FAILED", diagnostic);
    }

    /**
     * Creates a failure indicating that the source object could not be found in object storage.
     *
     * @param ref a human-readable reference to the missing object
     * @return an {@link ExtractionFailure} with code {@code ERROR_OBJECT_NOT_FOUND}
     */
    public static ExtractionFailure objectNotFound(String ref) {
        return new ExtractionFailure(
                "ERROR_OBJECT_NOT_FOUND",
                "Source object not found: " + ref);
    }

    public static ExtractionFailure objectChanged(String diagnostic) {
        return new ExtractionFailure("ERROR_OBJECT_CHANGED", diagnostic);
    }

    public static ExtractionFailure invalidObjectReference(String diagnostic) {
        return new ExtractionFailure("ERROR_INVALID_OBJECT_REFERENCE", diagnostic);
    }

    public static ExtractionFailure invalidRequest(String diagnostic) {
        return new ExtractionFailure("ERROR_INVALID_REQUEST", diagnostic);
    }

    public static ExtractionFailure timeout(String diagnostic) {
        return new ExtractionFailure("ERROR_TIMEOUT", diagnostic);
    }

    public static ExtractionFailure expired(String diagnostic) {
        return new ExtractionFailure("ERROR_EXPIRED", diagnostic);
    }

    public static ExtractionFailure internalError(String diagnostic) {
        return new ExtractionFailure("ERROR_INTERNAL_ERROR", diagnostic);
    }
}
