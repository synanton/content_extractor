package org.synanton.extraction.spi.model;

import java.util.Map;

/**
 * Normalized structural text and per-element UTF-16 ranges.
 *
 * <p>The text is the canonical representation produced by the adapter. Element ranges are
 * half-open UTF-16 code-unit offsets into {@link #text()}. Non-semantic preamble content may be
 * present in the text without a corresponding element range.
 *
 * @param text                 canonical structural text
 * @param normalizationProfile normalization profile identifier
 * @param normalizationVersion normalization profile version
 * @param elementRanges        element ID to canonical range
 */
public record CanonicalText(
        String text,
        String normalizationProfile,
        String normalizationVersion,
        Map<String, CanonicalRange> elementRanges) {

    public CanonicalText {
        if (text == null) {
            throw new IllegalArgumentException("canonical text must not be null");
        }
        if (normalizationProfile == null || normalizationProfile.isBlank()) {
            throw new IllegalArgumentException("normalizationProfile must not be blank");
        }
        if (normalizationVersion == null || normalizationVersion.isBlank()) {
            throw new IllegalArgumentException("normalizationVersion must not be blank");
        }
        elementRanges = Map.copyOf(elementRanges);
        for (Map.Entry<String, CanonicalRange> entry : elementRanges.entrySet()) {
            CanonicalRange range = entry.getValue();
            if (range.end() > text.length()) {
                throw new IllegalArgumentException(
                        "canonical range out of bounds for " + entry.getKey() + ": "
                                + range.start() + ".." + range.end() + " > " + text.length());
            }
        }
    }
}
