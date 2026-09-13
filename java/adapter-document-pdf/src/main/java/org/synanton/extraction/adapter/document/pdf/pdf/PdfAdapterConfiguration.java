package org.synanton.extraction.adapter.document.pdf.pdf;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.synanton.extraction.spi.spi.model.AdapterResult;

/**
 * Spring configuration that wires the {@link PdfModalityAdapter}.
 *
 * <p>PDF extraction runs the real OpenDataLoader library in-process — there is no
 * external service to point at. {@code extraction.processors.opendataloader.enabled}
 * (default {@code true}) is an operational escape hatch to disable the PDF modality
 * without a redeploy; when disabled, the adapter returns
 * {@link AdapterResult#unsupported(String)} for every request.
 */
@Configuration
public class PdfAdapterConfiguration {

    @Value("${extraction.processors.opendataloader.enabled:true}")
    private boolean openDataLoaderEnabled;

    @Bean
    public PdfModalityAdapter pdfModalityAdapter() {
        if (!openDataLoaderEnabled) {
            return new PdfModalityAdapter();
        }
        OpenDataLoaderClient client = new OpenDataLoaderClient(new ObjectMapper());
        return new PdfModalityAdapter(client);
    }
}
