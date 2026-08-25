package synanton.extraction.spi.model;

import java.time.Instant;

/**
 * Domain representation of one extraction request item, after validation and assignment of an
 * operation ID.
 *
 * @param operationId     the platform-assigned unique identifier for this operation
 * @param tenantId        the tenant that submitted the request
 * @param idempotencyKey  the caller-supplied idempotency key
 * @param contentRefId    the caller-supplied content reference identifier
 * @param source          the object storage reference for the source content
 * @param mediaType       the IANA media type of the source content
 * @param options         the extraction feature options for this request
 * @param priority        the scheduling priority hint
 * @param expiresAt       the instant after which the operation may be expired
 */
public record ExtractionRequest(
        String operationId,
        String tenantId,
        String idempotencyKey,
        String contentRefId,
        ObjectRef source,
        String mediaType,
        ExtractionOptions options,
        String priority,
        Instant expiresAt) {
}
