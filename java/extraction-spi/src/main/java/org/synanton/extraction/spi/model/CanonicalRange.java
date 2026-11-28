package org.synanton.extraction.spi.model;

/**
 * Half-open UTF-16 code-unit range into a {@link CanonicalText} projection.
 *
 * @param start inclusive offset
 * @param end   exclusive offset
 */
public record CanonicalRange(int start, int end) {
    public CanonicalRange {
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("canonical range must be non-empty: " + start + ".." + end);
        }
    }

    public int length() {
        return end - start;
    }
}
