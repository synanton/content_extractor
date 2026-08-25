package synanton.extraction.spi.model;

/**
 * Identifies source content in object storage. The plane reads bytes from storage
 * directly; content is never transported through the gRPC contract.
 *
 * @param bucket    the object storage bucket name
 * @param key       the object key within the bucket
 * @param version   the object version identifier (may be null if versioning is not used)
 * @param sha256    the hex-encoded SHA-256 digest of the object bytes for integrity verification
 * @param sizeBytes the size of the object in bytes
 */
public record ObjectRef(
        String bucket,
        String key,
        String version,
        String sha256,
        long sizeBytes) {
}
