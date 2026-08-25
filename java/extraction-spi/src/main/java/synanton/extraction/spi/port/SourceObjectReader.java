package synanton.extraction.spi.port;

import synanton.extraction.spi.model.ObjectRef;

import java.io.IOException;
import java.io.InputStream;

/**
 * Opens a read-only stream of source content. The plane MUST NOT modify the source object
 * (rule §67.3).
 *
 * <p>Callers are responsible for closing the returned stream.
 */
public interface SourceObjectReader {

    /**
     * Opens and returns a stream of the object bytes identified by {@code ref}.
     *
     * @param ref the object storage reference
     * @return a readable, non-null stream of the object bytes
     * @throws IOException if the object cannot be opened (e.g. not found, access denied, I/O error)
     */
    InputStream read(ObjectRef ref) throws IOException;
}
