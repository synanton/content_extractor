package synanton.extraction.adapter.document.pdf;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * HTTP client for the OpenDataLoader PDF processing service.
 *
 * <p>Posts PDF bytes to {@code {baseUrl}/convert} as multipart/form-data with field "file".
 * Returns the parsed JSON response.
 */
public class OpenDataLoaderClient {

    private static final Logger log = LoggerFactory.getLogger(OpenDataLoaderClient.class);

    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenDataLoaderClient(String baseUrl, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * Sends a PDF to the OpenDataLoader service and returns the parsed response.
     *
     * @param pdfBytes the raw PDF bytes to process
     * @return the parsed response from the service
     * @throws OpenDataLoaderException if the HTTP call fails or the response cannot be parsed
     */
    public OdlResponse extract(byte[] pdfBytes) {
        String boundary = "----SynBoundary" + System.currentTimeMillis();
        byte[] body = buildMultipartBody(boundary, pdfBytes);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/convert"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new OpenDataLoaderException("OpenDataLoader returned HTTP " + response.statusCode());
            }
            return objectMapper.readValue(response.body(), OdlResponse.class);
        } catch (OpenDataLoaderException e) {
            throw e;
        } catch (Exception e) {
            throw new OpenDataLoaderException("Failed to call OpenDataLoader: " + e.getMessage(), e);
        }
    }

    private byte[] buildMultipartBody(String boundary, byte[] pdfBytes) {
        String prefix = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"document.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n";
        String suffix = "\r\n--" + boundary + "--\r\n";
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[prefixBytes.length + pdfBytes.length + suffixBytes.length];
        System.arraycopy(prefixBytes, 0, result, 0, prefixBytes.length);
        System.arraycopy(pdfBytes, 0, result, prefixBytes.length, pdfBytes.length);
        System.arraycopy(suffixBytes, 0, result, prefixBytes.length + pdfBytes.length, suffixBytes.length);
        return result;
    }
}
