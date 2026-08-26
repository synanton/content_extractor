package synanton.extraction.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * Domain command to submit one or more artifacts as a single operation.
 */
public record SubmitExtractionCommand(
        String tenantId,
        String idempotencyKey,
        List<ExtractionItemCommand> items,
        String priority,
        Instant expiresAt) {

    public SubmitExtractionCommand {
        items = List.copyOf(items);
    }
}
