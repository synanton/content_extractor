package org.synanton.extraction.extraction.domain.model;

import org.synanton.extraction.spi.spi.model.ExtractionOptions;
import org.synanton.extraction.spi.spi.model.ObjectRef;

/**
 * Domain command for one artifact within a submit request.
 */
public record ExtractionItemCommand(
        String contentRefId,
        ObjectRef source,
        String mediaType,
        ExtractionOptions options) {
}
