package synanton.extraction.spi.model;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Result from a {@link synanton.extraction.spi.port.ModalityAdapter}.
 *
 * <p>Either {@code document} is present (success or partial extraction) or {@code failure} is
 * present (hard failure). The two fields are mutually exclusive: exactly one must be non-null.
 * {@code featureStates} is always populated.
 *
 * @param document      the normalised document (non-null on success, null on failure)
 * @param featureStates the outcome of each extraction feature, keyed by feature name
 * @param failure       the contract-level failure descriptor (non-null on failure, null on success)
 */
public record AdapterResult(
        NormalizedDocument document,
        Map<String, FeatureOutcome> featureStates,
        ExtractionFailure failure) {

    /**
     * Compact constructor that enforces the XOR invariant and defensively copies the feature map.
     *
     * @throws IllegalArgumentException if both {@code document} and {@code failure} are null,
     *                                  or if both are non-null
     */
    public AdapterResult {
        if ((document == null) == (failure == null)) {
            throw new IllegalArgumentException(
                    "Exactly one of document or failure must be non-null (XOR invariant).");
        }
        featureStates = Map.copyOf(featureStates);
    }

    /**
     * Creates a successful {@link AdapterResult} carrying the extracted document.
     *
     * @param document      the normalised document produced by the adapter
     * @param featureStates the outcome of each requested extraction feature
     * @return a success result
     */
    public static AdapterResult success(NormalizedDocument document,
                                        Map<String, FeatureOutcome> featureStates) {
        return new AdapterResult(document, featureStates, null);
    }

    /**
     * Creates a failed {@link AdapterResult} with no document.
     *
     * @param failure       the contract-level failure descriptor
     * @param featureStates the outcome of each extraction feature (typically all FAILED or UNSUPPORTED)
     * @return a failure result
     */
    public static AdapterResult failed(ExtractionFailure failure,
                                       Map<String, FeatureOutcome> featureStates) {
        return new AdapterResult(null, featureStates, failure);
    }

    /**
     * Creates a failure result indicating that the adapter does not support the given media type.
     * All known feature outcomes are set to {@link FeatureOutcome#UNSUPPORTED}.
     *
     * @param mediaType the unsupported IANA media type
     * @return a failure result with all features marked as {@code UNSUPPORTED}
     */
    public static AdapterResult unsupported(String mediaType) {
        Map<String, FeatureOutcome> allUnsupported = Arrays.stream(FeatureOutcome.values())
                .filter(outcome -> outcome != FeatureOutcome.UNSUPPORTED)
                .collect(Collectors.toMap(
                        FeatureOutcome::name,
                        ignored -> FeatureOutcome.UNSUPPORTED));
        return failed(ExtractionFailure.unsupportedMediaType(mediaType), allUnsupported);
    }

    /**
     * Returns {@code true} if this result represents a success (i.e. a document is present).
     *
     * @return {@code true} when {@code failure} is null
     */
    public boolean isSuccess() {
        return failure == null;
    }
}
