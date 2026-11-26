package org.synanton.extraction.spi.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceSpanTest {

    @Test
    void rejectsZeroLengthSpan() {
        assertThatThrownBy(() -> new SourceSpan("loc", 4, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
    }

    @Test
    void rejectsNegativeAndReversedSpans() {
        assertThatThrownBy(() -> new SourceSpan("loc", -1, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceSpan("loc", 5, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
