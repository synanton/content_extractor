package synanton.extraction.spi.model;

/**
 * Outcome of a single extraction feature for a processed item.
 */
public enum FeatureOutcome {

    /** The feature was requested and successfully applied. */
    APPLIED,

    /** The feature was not requested by the caller. */
    NOT_REQUESTED,

    /** The feature is not applicable to this media type or content. */
    NOT_APPLICABLE,

    /** The adapter does not support this feature. */
    UNSUPPORTED,

    /** The feature was attempted but failed. */
    FAILED,

    /** The feature was partially applied; some output was produced but the result is incomplete. */
    PARTIAL
}
