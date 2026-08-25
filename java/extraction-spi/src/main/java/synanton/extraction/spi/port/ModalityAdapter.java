package synanton.extraction.spi.port;

import synanton.extraction.spi.model.AdapterResult;
import synanton.extraction.spi.model.ExtractionRequest;

import java.io.InputStream;

/**
 * SPI interface for modality adapters. Each adapter handles one or more media types.
 * The processor identity is hidden from the platform (rule §67.2).
 *
 * <p>Adapters are registered with the extraction plane and selected per-request via
 * {@link #supports(String)}. Implementations must be stateless and thread-safe.
 */
public interface ModalityAdapter {

    /**
     * Returns {@code true} if this adapter can process content of the given media type.
     *
     * @param mediaType the IANA media type of the source content
     * @return {@code true} when this adapter supports the media type
     */
    boolean supports(String mediaType);

    /**
     * Extracts structured content from the source bytes.
     *
     * <p>The caller is responsible for closing the {@code source} stream after this method
     * returns. Implementations must not close the stream themselves.
     *
     * @param request the validated extraction request carrying options and metadata
     * @param source  a read-only stream of the source object bytes
     * @return the adapter result containing either a normalised document or a failure descriptor
     */
    AdapterResult extract(ExtractionRequest request, InputStream source);

    /**
     * Returns a stable identifier for this adapter implementation.
     *
     * <p>Adapters should override this method to return a meaningful identifier such as
     * {@code "pdf-adapter-pdfbox"} or {@code "text-adapter-plain"}.
     *
     * @return a stable, human-readable processor identifier
     */
    default String processorId() {
        return "unknown-adapter";
    }
}
