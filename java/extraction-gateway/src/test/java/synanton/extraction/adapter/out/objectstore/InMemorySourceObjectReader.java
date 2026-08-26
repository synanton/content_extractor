package synanton.extraction.adapter.out.objectstore;

import synanton.extraction.spi.model.ObjectRef;
import synanton.extraction.spi.port.SourceObjectReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory object store used by unit tests. */
public class InMemorySourceObjectReader implements SourceObjectReader {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    public void put(String bucket, String key, byte[] bytes) {
        objects.put(bucket + "/" + key, bytes);
    }

    @Override
    public InputStream read(ObjectRef ref) throws IOException {
        byte[] bytes = objects.get(ref.bucket() + "/" + ref.key());
        if (bytes == null) {
            throw new IOException("not found");
        }
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public long contentLength(ObjectRef ref) throws IOException {
        byte[] bytes = objects.get(ref.bucket() + "/" + ref.key());
        if (bytes == null) {
            throw new IOException("not found");
        }
        return bytes.length;
    }
}
