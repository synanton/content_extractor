package org.synanton.extraction.spi.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalTextTest {

    @Test
    void rejectsRangesOutsideTheProjection() {
        assertThatThrownBy(() -> new CanonicalText(
                "Title", "scep-test", "1", Map.of("e1", new CanonicalRange(0, 6))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of bounds");
    }

    @Test
    void rejectsBlankNormalizationIdentity() {
        assertThatThrownBy(() -> new CanonicalText(
                "Title", "", "1", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("normalizationProfile");
        assertThatThrownBy(() -> new CanonicalText(
                "Title", "scep-test", " ", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("normalizationVersion");
    }

    @Test
    void rejectsEmptyOrReversedRanges() {
        assertThatThrownBy(() -> new CanonicalRange(3, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
        assertThatThrownBy(() -> new CanonicalRange(4, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
    }
}
