package synanton.extraction.adapter.document.pdf;

import org.junit.jupiter.api.Test;
import synanton.extraction.spi.model.AdapterResult;
import synanton.extraction.spi.model.ExtractionOptions;
import synanton.extraction.spi.model.ExtractionRequest;
import synanton.extraction.spi.model.ObjectRef;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PdfModalityAdapterTest {

    @Test
    void shouldSupportApplicationPdf() {
        PdfModalityAdapter adapter = new PdfModalityAdapter();
        assertThat(adapter.supports("application/pdf")).isTrue();
    }

    @Test
    void shouldNotSupportTextPlain() {
        PdfModalityAdapter adapter = new PdfModalityAdapter();
        assertThat(adapter.supports("text/plain")).isFalse();
    }

    @Test
    void shouldReturnUnsupportedResultWhenClientIsNotConfigured() {
        PdfModalityAdapter adapter = new PdfModalityAdapter();

        ExtractionRequest request = new ExtractionRequest(
                "op-1",
                "tenant-1",
                "idem-1",
                "content-ref-1",
                new ObjectRef("bucket", "key", null, null, 0L),
                "application/pdf",
                ExtractionOptions.defaults(),
                "normal",
                Instant.now().plusSeconds(3600)
        );
        InputStream source = new ByteArrayInputStream(new byte[]{0x25, 0x50, 0x44, 0x46}); // %PDF

        AdapterResult result = adapter.extract(request, source);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.failure().errorCode()).isEqualTo("ERROR_UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void shouldReturnProcessorId() {
        PdfModalityAdapter adapter = new PdfModalityAdapter();
        assertThat(adapter.processorId()).isEqualTo("opendataloader-pdf");
    }
}
