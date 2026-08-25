package synanton.extraction.adapter.document.pdf;

/**
 * Unchecked exception thrown when the OpenDataLoader HTTP service call fails or
 * the response cannot be parsed.
 */
public class OpenDataLoaderException extends RuntimeException {

    public OpenDataLoaderException(String message) {
        super(message);
    }

    public OpenDataLoaderException(String message, Throwable cause) {
        super(message, cause);
    }
}
