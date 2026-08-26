package synanton.extraction.adapter.document.pdf;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration that wires the {@link PdfModalityAdapter}.
 *
 * <p>When {@code extraction.processors.opendataloader.base-url} is absent or blank,
 * the adapter is registered without an HTTP client and will return
 * {@link synanton.extraction.spi.model.AdapterResult#unsupported(String)} for every request.
 */
@Configuration
public class PdfAdapterConfiguration {

    @Value("${extraction.processors.opendataloader.base-url}")
    private String openDataLoaderBaseUrl;

    @Bean
    public PdfModalityAdapter pdfModalityAdapter() {
        if (openDataLoaderBaseUrl == null || openDataLoaderBaseUrl.isBlank()) {
            return new PdfModalityAdapter();
        }
        ObjectMapper objectMapper = new ObjectMapper();
        OpenDataLoaderClient client = new OpenDataLoaderClient(openDataLoaderBaseUrl, objectMapper);
        return new PdfModalityAdapter(client);
    }
}
