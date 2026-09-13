package org.synanton.extraction.domain.model;

import org.synanton.extraction.spi.model.ExtractionOptions;
import org.synanton.extraction.spi.model.ObjectRef;

/**
 * Domain command for one artifact within a submit request.
 */
public record ExtractionItemCommand(
        String contentRefId,
        ObjectRef source,
        String mediaType,
        ExtractionOptions options) {
}
